package sa.com.cloudsolutions.antikythera.generator;

/**
 * Reasons a discovered type may be excluded from fallback unit-test generation.
 */
public enum SkipReason {
    ANNOTATION_TYPE,
    INTERFACE,
    ENUM,
    RECORD,
    ABSTRACT_CLASS,
    PRIVATE_INNER_CLASS,
    CONTROLLER,
    CONTROLLER_ADVICE,
    ENTITY,
    EMBEDDABLE,
    MAPPED_SUPERCLASS,
    ID_CLASS,
    SPRING_DATA_REPOSITORY,
    CONFIGURATION,
    SPRING_BOOT_APPLICATION,
    MAIN_CLASS,
    AOP_ASPECT,
    FEIGN_CLIENT,
    CONSTANT_CLASS,
    EXCEPTION_CLASS,
    DATA_CARRIER_BY_STRUCTURE,
    DATA_CARRIER_BY_ANNOTATION,
    DATA_CARRIER_BY_NAME,
    NO_TESTABLE_METHODS,
    USER_SKIP_LIST
}
