package co.edu.uniandes.culturas.web.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Traduce excepciones a respuestas RFC 9457 ({@code application/problem+json}).
 *
 * <p>El manejador de 2023 cubría exactamente dos excepciones y mapeaba
 * {@code BusinessLogicException} a <strong>412 Precondition Failed</strong>,
 * incluido el caso de un nombre vacío. Todo lo demás caía en el whitelabel de
 * Spring: las ocho restricciones UNIQUE de la base salían como 500 con traza de
 * Hibernate, y un {@code ?id=abc} también.
 *
 * <p>Extender {@link ResponseEntityExceptionHandler} da además el tratamiento
 * de las excepciones propias de Spring MVC (cuerpo ilegible, método no
 * permitido, parámetro ausente) sin escribir un handler por cada una.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final URI TYPE_NOT_FOUND   = URI.create("https://culturas.uniandes.edu.co/errors/recurso-no-encontrado");
    private static final URI TYPE_DOMAIN_RULE = URI.create("https://culturas.uniandes.edu.co/errors/regla-de-dominio");
    private static final URI TYPE_CONFLICT    = URI.create("https://culturas.uniandes.edu.co/errors/conflicto");
    private static final URI TYPE_VALIDATION  = URI.create("https://culturas.uniandes.edu.co/errors/validacion");
    private static final URI TYPE_INTERNAL    = URI.create("https://culturas.uniandes.edu.co/errors/interno");

    /**
     * Código SQLSTATE que levanta el trigger de las estrellas Michelin.
     * Postgres lo devuelve como violación de CHECK aunque la regla la imponga
     * un trigger, porque así se declara con {@code USING ERRCODE}.
     */
    private static final String SQLSTATE_CHECK_VIOLATION = "23514";
    private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";
    private static final String SQLSTATE_FK_VIOLATION     = "23503";

    @ExceptionHandler(ResourceNotFoundException.class)
    ProblemDetail handleNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        ProblemDetail problem = base(HttpStatus.NOT_FOUND, ex.getMessage(), TYPE_NOT_FOUND, request);
        problem.setTitle("Recurso no encontrado");
        problem.setProperty("recurso", ex.getResource());
        problem.setProperty("identificador", String.valueOf(ex.getIdentifier()));
        return problem;
    }

    @ExceptionHandler(DomainRuleException.class)
    ProblemDetail handleDomainRule(DomainRuleException ex, HttpServletRequest request) {
        ProblemDetail problem = base(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), TYPE_DOMAIN_RULE, request);
        problem.setTitle("Regla de dominio incumplida");
        problem.setProperty("regla", ex.getRule());
        return problem;
    }

    /**
     * Las restricciones de la base dejan de ser errores 500.
     *
     * <p>Se inspecciona el SQLSTATE en vez del texto del mensaje: el texto
     * cambia con la versión de Postgres y con el idioma del servidor, y aquí el
     * cluster corre en inglés mientras la aplicación responde en español.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        String sqlState = extractSqlState(ex);

        if (SQLSTATE_CHECK_VIOLATION.equals(sqlState)) {
            // Incluye el trigger de las estrellas Michelin y los CHECK de
            // formato ISO, dificultad y posiciones.
            ProblemDetail problem = base(HttpStatus.UNPROCESSABLE_ENTITY,
                    "La operación viola una regla de integridad del catálogo.", TYPE_DOMAIN_RULE, request);
            problem.setTitle("Regla de dominio incumplida");
            problem.setProperty("regla", "check-violation");
            log.info("Violación de CHECK en {}: {}", request.getRequestURI(), ex.getMostSpecificCause().getMessage());
            return problem;
        }

        HttpStatus status = HttpStatus.CONFLICT;
        String detail = switch (sqlState == null ? "" : sqlState) {
            case SQLSTATE_UNIQUE_VIOLATION -> "Ya existe un registro con esos valores únicos.";
            case SQLSTATE_FK_VIOLATION -> "La operación dejaría referencias huérfanas o apunta a un recurso inexistente.";
            default -> "La operación entra en conflicto con el estado actual de los datos.";
        };

        ProblemDetail problem = base(status, detail, TYPE_CONFLICT, request);
        problem.setTitle("Conflicto de integridad");
        // El mensaje de la causa lleva el nombre de la constraint, que es útil
        // en el log pero no se expone: revelaría el esquema al cliente.
        log.info("Violación de integridad ({}) en {}: {}",
                sqlState, request.getRequestURI(), ex.getMostSpecificCause().getMessage());
        return problem;
    }

    /**
     * Dos escrituras concurrentes sobre la misma fila. En 2023 no existía
     * {@code @Version}, así que la segunda pisaba a la primera en silencio.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail handleOptimisticLock(OptimisticLockingFailureException ex, HttpServletRequest request) {
        ProblemDetail problem = base(HttpStatus.CONFLICT,
                "Otro usuario modificó este recurso mientras lo editabas. Vuelve a cargarlo e inténtalo de nuevo.",
                TYPE_CONFLICT, request);
        problem.setTitle("Conflicto de concurrencia");
        problem.setProperty("regla", "optimistic-lock");
        return problem;
    }

    /** Invariantes que las entidades comprueban por su cuenta. */
    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail handleIllegalState(IllegalStateException ex, HttpServletRequest request) {
        ProblemDetail problem = base(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), TYPE_DOMAIN_RULE, request);
        problem.setTitle("Regla de dominio incumplida");
        return problem;
    }

    /**
     * Credenciales incorrectas.
     *
     * <p>Sin este manejador la excepción caería en el catch-all y saldría como
     * 500. El detalle es genérico a propósito: distinguir "no existe" de
     * "contraseña incorrecta" permitiría enumerar cuentas registradas.
     */
    @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
    ProblemDetail handleAuthentication(org.springframework.security.core.AuthenticationException ex,
                                       HttpServletRequest request) {
        ProblemDetail problem = base(HttpStatus.UNAUTHORIZED,
                "Usuario o contraseña incorrectos.", TYPE_VALIDATION, request);
        problem.setTitle("Credenciales inválidas");
        log.debug("Autenticación fallida en {}: {}", request.getRequestURI(), ex.getMessage());
        return problem;
    }

    /** Autenticado pero sin permiso: 403, no 500. */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    ProblemDetail handleAccessDenied(org.springframework.security.access.AccessDeniedException ex,
                                     HttpServletRequest request) {
        ProblemDetail problem = base(HttpStatus.FORBIDDEN,
                "No tienes permiso para realizar esta operación.", TYPE_DOMAIN_RULE, request);
        problem.setTitle("Acceso denegado");
        return problem;
    }

    /** Último recurso: nada de trazas hacia el cliente, todo al log. */
    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        // Se registra con la traza completa y se responde con un mensaje
        // genérico: el whitelabel de Spring filtraba detalles internos.
        log.error("Error no controlado en {} {}", request.getMethod(), request.getRequestURI(), ex);
        ProblemDetail problem = base(HttpStatus.INTERNAL_SERVER_ERROR,
                "Ocurrió un error inesperado procesando la petición.", TYPE_INTERNAL, request);
        problem.setTitle("Error interno");
        return problem;
    }

    // --- Excepciones propias de Spring MVC ----------------------------------

    /** Errores de Bean Validation, campo a campo. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> Map.of(
                        "campo", fieldError.getField(),
                        "mensaje", fieldError.getDefaultMessage() == null ? "valor inválido" : fieldError.getDefaultMessage()))
                .toList();

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Alguno de los campos enviados no es válido.");
        problem.setType(TYPE_VALIDATION);
        problem.setTitle("Validación fallida");
        problem.setProperty("timestamp", Instant.now());
        problem.setProperty("errores", errors);

        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * Validación de parámetros por la vía de {@code @Validated}.
     *
     * <p>Hay DOS caminos para lo mismo y sólo se disparan uno u otro:
     * <ul>
     *   <li>Con {@code @Validated} en la clase, Spring crea un proxy y lanza
     *       {@code ConstraintViolationException} — este manejador.
     *   <li>Sin ella, la validación de métodos incorporada desde Spring 6.1
     *       lanza {@code HandlerMethodValidationException} — el de abajo.
     * </ul>
     *
     * <p>Se cubren los dos porque el proyecto usa {@code @Validated} en unos
     * controladores y no en otros, y porque quitarla de uno cambiaría en
     * silencio de rama. Sin ninguno de los dos, {@code ?limit=999} contra un
     * {@code @Max(50)} salía como <strong>500</strong>: un error del cliente
     * disfrazado de avería del servidor, que además dispara alertas que no
     * corresponden.
     *
     * <p>Lo detectó ApiContractIT comprobando los límites del parámetro de
     * búsqueda; no se había notado porque la interfaz nunca manda un límite
     * fuera de rango.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex,
                                                           HttpServletRequest request) {
        List<Map<String, String>> errors = ex.getConstraintViolations().stream()
                .map(violation -> Map.of(
                        // El path incluye el nombre del método: se queda el
                        // último segmento, que es el del parámetro.
                        "campo", lastSegment(violation.getPropertyPath().toString()),
                        "mensaje", violation.getMessage()))
                .toList();

        ProblemDetail problem = base(HttpStatus.BAD_REQUEST,
                "Alguno de los parámetros de la petición no es válido.", TYPE_VALIDATION, request);
        problem.setTitle("Parámetros inválidos");
        problem.setProperty("errores", errors);

        return ResponseEntity.badRequest().body(problem);
    }

    private static String lastSegment(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path : path.substring(dot + 1);
    }

    /**
     * Validación de <strong>parámetros</strong>, no de cuerpo.
     *
     * <p>La rama sin {@code @Validated}: desde Spring 6.1 la validación de
     * argumentos de controlador está incorporada y lanza esto en vez de
     * {@code MethodArgumentNotValidException}, que sólo cubre el cuerpo.
     */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        List<Map<String, String>> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> Map.of(
                                "campo", result.getMethodParameter().getParameterName() == null
                                        ? "parámetro" : result.getMethodParameter().getParameterName(),
                                "mensaje", error.getDefaultMessage() == null
                                        ? "valor inválido" : error.getDefaultMessage())))
                .toList();

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Alguno de los parámetros de la petición no es válido.");
        problem.setType(TYPE_VALIDATION);
        problem.setTitle("Parámetros inválidos");
        problem.setProperty("timestamp", Instant.now());
        problem.setProperty("errores", errors);

        return ResponseEntity.badRequest().body(problem);
    }

    /** {@code ?id=abc} contra un parámetro Long: 400, no 500. */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            org.springframework.beans.TypeMismatchException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {

        String name = ex instanceof MethodArgumentTypeMismatchException mismatch ? mismatch.getName() : "parámetro";
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "El valor de '%s' no tiene el tipo esperado.".formatted(name));
        problem.setType(TYPE_VALIDATION);
        problem.setTitle("Parámetro inválido");
        problem.setProperty("timestamp", Instant.now());
        problem.setProperty("parametro", name);

        return ResponseEntity.badRequest().body(problem);
    }

    // --- Utilidades ---------------------------------------------------------

    private ProblemDetail base(HttpStatus status, String detail, URI type, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(type);
        problem.setInstance(URI.create(request.getRequestURI()));
        // Instant y no un formato manual: el de 2023 usaba "dd-MM-yyyy hh:mm:ss"
        // con hh minúscula (reloj de 12 horas) y sin AM/PM, así que las 13:00 y
        // la 01:00 se serializaban idénticas.
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    private String extractSqlState(DataIntegrityViolationException ex) {
        Throwable cause = ex.getMostSpecificCause();
        return cause instanceof java.sql.SQLException sqlException ? sqlException.getSQLState() : null;
    }
}
