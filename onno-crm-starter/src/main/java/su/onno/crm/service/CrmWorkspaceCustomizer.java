package su.onno.crm.service;

/** Developer-owned inbox presentation; declare ordered Spring beans in the consuming application. */
@FunctionalInterface
public interface CrmWorkspaceCustomizer {
    CrmWorkspaceService.Config customize(CrmWorkspaceService.Config defaults);
}
