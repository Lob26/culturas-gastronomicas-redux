package co.edu.uniandes.culturas.domain;

/**
 * Dificultad de preparación de una receta.
 *
 * <p>Los nombres van en español porque son vocabulario del dominio y se
 * persisten literalmente: la columna lleva un CHECK que sólo acepta estos tres
 * valores, así que renombrar una constante exige una migración.
 */
public enum Difficulty {
    FACIL,
    MEDIA,
    DIFICIL
}
