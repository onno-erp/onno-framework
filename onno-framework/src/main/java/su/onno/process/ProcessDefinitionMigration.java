package su.onno.process;

/**
 * Typed migration between two registered versions of the same durable process definition.
 *
 * @param <FP> source payload type
 * @param <FS> source step enum
 * @param <TP> target payload type
 * @param <TS> target step enum
 */
public interface ProcessDefinitionMigration<
        FP,
        FS extends Enum<FS> & ProcessStepKey,
        TP,
        TS extends Enum<TS> & ProcessStepKey> {

    /** Definition version whose durable instances this migration accepts. */
    ProcessDefinition<FP, FS> from();

    /** Later definition version produced by this migration. */
    ProcessDefinition<TP, TS> to();

    /** Map the payload and every active token to the target definition. */
    ProcessMigrationResult<TP, TS> migrate(ProcessMigrationState<FP, FS> state);
}
