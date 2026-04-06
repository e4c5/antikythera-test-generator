package sa.com.cloudsolutions.antikythera.generator;

/**
 * Classification of exception types based on their triggering conditions.
 */
public enum ExceptionType {
    /**
     * Exception is always thrown regardless of input data.
     * Example: throw new UnsupportedOperationException("Not implemented");
     */
    UNCONDITIONAL,
    
    /**
     * Exception is thrown only with certain input values that fail validation.
     * Example: if (name.isEmpty()) throw new IllegalArgumentException();
     */
    CONDITIONAL_ON_DATA,
    
    /**
     * Exception is thrown only when iterating over a non-empty collection.
     * Example: for (Item item : items) { if (item.isInvalid()) throw exception; }
     */
    CONDITIONAL_ON_LOOP,
    
    /**
     * Exception is thrown based on object state or external dependencies.
     * Example: if (repository.findById(id).isEmpty()) throw new NotFoundException();
     */
    CONDITIONAL_ON_STATE
}
