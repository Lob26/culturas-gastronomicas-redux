package co.edu.uniandes.culturas.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Deriva identificadores de URL estables a partir de un nombre.
 *
 * <p>El slug lo calcula el servidor y no lo acepta del cliente: dejarlo en
 * manos de quien llama permitiría que dos culturas compartieran URL, o que el
 * slug dejara de corresponderse con el nombre tras una edición.
 */
public final class Slugs {

    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");
    private static final Pattern EDGE_HYPHENS = Pattern.compile("(^-+)|(-+$)");
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");

    private Slugs() {
    }

    /**
     * {@code "Cocina italiana"} produce {@code "cocina-italiana"}, y
     * {@code "Bandeja Paisa (típica)"} produce {@code "bandeja-paisa-tipica"}.
     *
     * <p>La normalización NFD separa cada letra acentuada en la letra base más
     * su marca diacrítica, de modo que al descartar las marcas queda la letra
     * ASCII. Sin ese paso, «ñ» y «í» simplemente desaparecerían del slug y
     * «Japón» quedaría como «japn».
     */
    public static String of(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("No se puede derivar un slug de un texto vacío");
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFD);
        String ascii = COMBINING_MARKS.matcher(normalized).replaceAll("");
        String lowered = ascii.toLowerCase(Locale.ROOT);
        String hyphenated = NON_ALPHANUMERIC.matcher(lowered).replaceAll("-");
        String trimmed = EDGE_HYPHENS.matcher(hyphenated).replaceAll("");

        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(
                    "El nombre '%s' no contiene caracteres utilizables para un slug".formatted(text));
        }
        return trimmed;
    }
}
