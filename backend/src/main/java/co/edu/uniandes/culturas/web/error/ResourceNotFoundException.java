package co.edu.uniandes.culturas.web.error;

/**
 * El recurso solicitado no existe. Se traduce a HTTP 404.
 *
 * <p>Lleva el tipo y el identificador buscados, al contrario que la excepción
 * de 2023, cuyo constructor sólo aceptaba un enum de nombres de entidad: el
 * mensaje resultante era {@code "CULTURE NOT FOUND"}, sin forma de saber qué
 * id se había pedido ni desde dónde.
 */
public class ResourceNotFoundException extends RuntimeException {

    private final String resource;
    private final Object identifier;

    public ResourceNotFoundException(String resource, Object identifier) {
        super("No existe %s con identificador '%s'".formatted(resource, identifier));
        this.resource = resource;
        this.identifier = identifier;
    }

    public String getResource() {
        return resource;
    }

    public Object getIdentifier() {
        return identifier;
    }
}
