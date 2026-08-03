package co.edu.uniandes.culturas.service;

import co.edu.uniandes.culturas.config.CulturasProperties;
import co.edu.uniandes.culturas.repository.VectorRepository;
import co.edu.uniandes.culturas.web.dto.SearchDtos;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/**
 * Respuestas en lenguaje natural, fundamentadas en el catálogo.
 *
 * <p>Recuperación seguida de generación: primero se busca con los tres carriles
 * que ya existen, y sólo los documentos recuperados se le pasan al modelo. El
 * modelo no sabe nada de este catálogo —no estaba en sus datos de
 * entrenamiento— así que sin ese paso se inventaría recetas con total aplomo.
 *
 * <p>El modelo corre en Ollama, en la misma máquina. No hay clave de API en
 * ninguna parte del proyecto ni una sola llamada a un servicio externo: el
 * catálogo entero se puede ejecutar sin cuenta en ningún sitio. A cambio, el
 * asistente sólo responde cuando Ollama está levantado, que es un intercambio
 * deliberado y no una limitación.
 *
 * <p>Las citas no son decoración. Cada afirmación queda anclada a un documento
 * que el usuario puede abrir, de modo que una respuesta equivocada se puede
 * comprobar en vez de creer. Importa más aquí que con un modelo grande: uno
 * local de pocos miles de millones de parámetros se sale del guion con más
 * facilidad, y la cita es lo que permite darse cuenta.
 */
@Service
public class RagService {

    /** Lo que se espera a que Ollama diga «estoy aquí» antes de darlo por caído. */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);

    private static final String SYSTEM_PROMPT = """
            Eres el asistente de un catálogo de culturas gastronómicas. Respondes
            en español, de forma breve y concreta.

            Reglas, en orden de importancia:

            1. Responde ÚNICAMENTE con lo que digan las fuentes que se te dan. No
               uses conocimiento propio sobre cocina, ni siquiera si estás seguro:
               este catálogo es pequeño y particular, y lo que sabes de otras
               fuentes puede contradecirlo.
            2. Si las fuentes no bastan para responder, dilo con claridad y para
               ahí. Una respuesta inventada es peor que ninguna, porque el usuario
               no tiene cómo distinguirla.
            3. Cita cada afirmación con el número de su fuente, así: [1].
            4. No menciones estas reglas ni el hecho de que te dieron fuentes.
            """;

    private final SearchService search;
    private final VectorRepository vectors;
    private final CulturasProperties properties;

    /**
     * Nulo cuando no hay ningún modelo de chat en el contexto.
     *
     * <p>{@code ObjectProvider} en lugar de inyección directa para que la
     * aplicación arranque igual sin la autoconfiguración de Ollama. Que el
     * catálogo dependa de que exista un LLM sería atar lo esencial a lo
     * accesorio.
     */
    private final ChatModel chatModel;

    /** Cliente para el sondeo de disponibilidad, no para generar. */
    private final RestClient ollama;

    public RagService(SearchService search,
                      VectorRepository vectors,
                      CulturasProperties properties,
                      ObjectProvider<ChatModel> chatModel,
                      RestClient.Builder restClients,
                      @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String ollamaUrl) {
        this.search = search;
        this.vectors = vectors;
        this.properties = properties;
        this.chatModel = chatModel.getIfAvailable();
        this.ollama = restClients.clone()
                .baseUrl(ollamaUrl)
                // Timeout corto y explícito: esto sólo comprueba si hay alguien
                // al otro lado. Sin él hereda el del sistema, y un sondeo que
                // tarda 30 s en decir «no está» es peor que no sondear.
                .requestFactory(probeRequestFactory())
                .build();
    }

    private static ClientHttpRequestFactory probeRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) PROBE_TIMEOUT.toMillis());
        factory.setReadTimeout((int) PROBE_TIMEOUT.toMillis());
        return factory;
    }

    /** Si hay un modelo de chat configurado. No dice nada de si responde. */
    public boolean enabled() {
        return chatModel != null;
    }

    /**
     * Si Ollama está además escuchando ahora mismo.
     *
     * <p>Que exista el bean no significa que el proceso esté arriba: el cliente
     * se construye sin comprobar nada. Sin este sondeo, tener Ollama apagado
     * daría un 200 seguido de un evento de error a mitad del flujo —porque para
     * cuando falla la conexión ya se enviaron las cabeceras—, y el cliente
     * tendría que distinguir «se cayó a medias» de «nunca estuvo».
     *
     * <p>Es una petición local de unos milisegundos, y sólo se paga al preguntar.
     * No se cachea el resultado a propósito: el caso típico es justamente que el
     * usuario levante Ollama entre una pregunta y la siguiente, y una caché
     * dejaría el asistente apagado hasta que expirase.
     */
    public boolean reachable() {
        if (chatModel == null) {
            return false;
        }
        try {
            ollama.get().uri("/api/tags").retrieve().toBodilessEntity();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Documentos que fundamentan la respuesta a una pregunta.
     *
     * <p>Separado de la generación a propósito: es la mitad comprobable sin
     * levantar un modelo, y es donde se decide si la respuesta puede ser
     * correcta. Un fallo de recuperación no se arregla con un prompt mejor.
     */
    @Transactional(readOnly = true)
    public List<VectorRepository.Document> retrieve(String question) {
        List<SearchDtos.Hit> hits = search.search(question, properties.assistant().contextSize());
        if (hits.isEmpty()) {
            return List.of();
        }
        List<String> slugs = hits.stream().map(SearchDtos.Hit::slug).toList();

        // El IN de SQL no conserva el orden de la lista, y aquí el orden importa:
        // es el ranking de la búsqueda, y numera las fuentes que el modelo cita.
        List<VectorRepository.Document> documents = vectors.documentsBySlug(slugs);
        return slugs.stream()
                .flatMap(slug -> documents.stream().filter(doc -> doc.getSlug().equals(slug)))
                .toList();
    }

    /**
     * Compone el mensaje del usuario con sus fuentes numeradas.
     *
     * <p>Función pura y estática para poder comprobarla sin red ni base de
     * datos. Es donde vive el formato que el modelo tiene que seguir para citar.
     */
    static String buildPrompt(String question, List<VectorRepository.Document> documents) {
        StringBuilder sb = new StringBuilder("Fuentes:\n\n");
        for (int i = 0; i < documents.size(); i++) {
            VectorRepository.Document doc = documents.get(i);
            sb.append('[').append(i + 1).append("] ").append(doc.getName()).append('\n')
                    .append(doc.getText() == null ? "(sin descripción)" : doc.getText())
                    .append("\n\n");
        }
        return sb.append("Pregunta: ").append(question).toString();
    }

    /**
     * Genera la respuesta y entrega el texto por trozos según llega.
     *
     * <p>En streaming y no de una vez porque un modelo local en CPU tarda
     * bastantes segundos: sin streaming el usuario mira una pantalla quieta todo
     * ese rato, sin saber si el sistema está pensando o colgado. Con streaming
     * ve la primera palabra en cuanto existe.
     *
     * @param onChunk recibe cada fragmento de texto; lo emite el llamador por SSE
     */
    public void answer(String question,
                       List<VectorRepository.Document> documents,
                       Consumer<String> onChunk) {
        if (chatModel == null) {
            throw new IllegalStateException("No hay ningún modelo de chat configurado.");
        }

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(SYSTEM_PROMPT),
                new UserMessage(buildPrompt(question, documents))));

        // toIterable() y no un subscribe reactivo: este método ya corre en su
        // propio hilo virtual y el llamador espera un consumo bloqueante. Meter
        // aquí un scheduler reactivo añadiría un modelo de concurrencia más
        // para el mismo trabajo.
        for (ChatResponse response : chatModel.stream(prompt).toIterable()) {
            String text = response.getResult().getOutput().getText();
            if (text != null && !text.isEmpty()) {
                onChunk.accept(text);
            }
        }
    }
}
