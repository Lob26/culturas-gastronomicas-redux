package co.edu.uniandes.culturas.web.error;

/**
 * La petición está bien formada pero viola una regla del dominio: borrar una
 * cultura que todavía tiene recetas, añadir una cuarta estrella Michelin,
 * calificar dos veces la misma receta. Se traduce a HTTP 422.
 *
 * <p>422 y no 400: la sintaxis es correcta y la validación de campos pasó; lo
 * que falla es una regla de negocio. Tampoco 412, que es lo que devolvía el
 * proyecto de 2023 para absolutamente todo, incluida una validación de campo
 * vacío.
 */
public class DomainRuleException extends RuntimeException {

    private final String rule;

    public DomainRuleException(String rule, String message) {
        super(message);
        this.rule = rule;
    }

    /** Identificador estable de la regla, para que el cliente pueda ramificar. */
    public String getRule() {
        return rule;
    }
}
