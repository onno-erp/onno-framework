package su.onno.metadata;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class MetadataRegistry {

    private final List<DashboardWidgetDescriptor> dashboardWidgets = new CopyOnWriteArrayList<>();
    private final Map<Class<?>, CatalogDescriptor> catalogs = new ConcurrentHashMap<>();
    private final Map<Class<?>, DocumentDescriptor> documents = new ConcurrentHashMap<>();
    private final Map<Class<?>, AccumulationRegisterDescriptor> registers = new ConcurrentHashMap<>();
    private final Map<Class<?>, EnumerationDescriptor> enumerations = new ConcurrentHashMap<>();
    private final Map<Class<?>, InformationRegisterDescriptor> informationRegisters = new ConcurrentHashMap<>();
    private final Map<Class<?>, ConstantDescriptor> constants = new ConcurrentHashMap<>();

    public void registerCatalog(CatalogDescriptor descriptor) {
        catalogs.put(descriptor.javaClass(), descriptor);
    }

    public void registerDocument(DocumentDescriptor descriptor) {
        documents.put(descriptor.javaClass(), descriptor);
    }

    public void registerAccumulation(AccumulationRegisterDescriptor descriptor) {
        registers.put(descriptor.javaClass(), descriptor);
    }

    public CatalogDescriptor getCatalogDescriptor(Class<?> clazz) {
        CatalogDescriptor descriptor = catalogs.get(clazz);
        if (descriptor == null) {
            throw new IllegalArgumentException("No catalog descriptor registered for " + clazz.getName());
        }
        return descriptor;
    }

    public DocumentDescriptor getDocumentDescriptor(Class<?> clazz) {
        DocumentDescriptor descriptor = documents.get(clazz);
        if (descriptor == null) {
            throw new IllegalArgumentException("No document descriptor registered for " + clazz.getName());
        }
        return descriptor;
    }

    public AccumulationRegisterDescriptor getRegisterDescriptor(Class<?> clazz) {
        AccumulationRegisterDescriptor descriptor = registers.get(clazz);
        if (descriptor == null) {
            throw new IllegalArgumentException("No register descriptor registered for " + clazz.getName());
        }
        return descriptor;
    }

    public Collection<CatalogDescriptor> allCatalogs() {
        return Collections.unmodifiableCollection(catalogs.values());
    }

    public Collection<DocumentDescriptor> allDocuments() {
        return Collections.unmodifiableCollection(documents.values());
    }

    public Collection<AccumulationRegisterDescriptor> allRegisters() {
        return Collections.unmodifiableCollection(registers.values());
    }

    public void registerEnumeration(EnumerationDescriptor descriptor) {
        enumerations.put(descriptor.javaClass(), descriptor);
    }

    public EnumerationDescriptor getEnumerationDescriptor(Class<?> clazz) {
        EnumerationDescriptor descriptor = enumerations.get(clazz);
        if (descriptor == null) {
            throw new IllegalArgumentException("No enumeration descriptor registered for " + clazz.getName());
        }
        return descriptor;
    }

    public boolean isEnumeration(Class<?> clazz) {
        return enumerations.containsKey(clazz);
    }

    public Collection<EnumerationDescriptor> allEnumerations() {
        return Collections.unmodifiableCollection(enumerations.values());
    }

    public void registerInformationRegister(InformationRegisterDescriptor descriptor) {
        informationRegisters.put(descriptor.javaClass(), descriptor);
    }

    public InformationRegisterDescriptor getInformationRegisterDescriptor(Class<?> clazz) {
        InformationRegisterDescriptor descriptor = informationRegisters.get(clazz);
        if (descriptor == null) {
            throw new IllegalArgumentException("No information register descriptor registered for " + clazz.getName());
        }
        return descriptor;
    }

    public Collection<InformationRegisterDescriptor> allInformationRegisters() {
        return Collections.unmodifiableCollection(informationRegisters.values());
    }

    public void registerConstant(ConstantDescriptor descriptor) {
        constants.put(descriptor.javaClass(), descriptor);
    }

    public ConstantDescriptor getConstantDescriptor(Class<?> clazz) {
        ConstantDescriptor descriptor = constants.get(clazz);
        if (descriptor == null) {
            throw new IllegalArgumentException("No constant descriptor registered for " + clazz.getName());
        }
        return descriptor;
    }

    public Collection<ConstantDescriptor> allConstants() {
        return Collections.unmodifiableCollection(constants.values());
    }

    public void registerDashboardWidgets(List<DashboardWidgetDescriptor> widgets) {
        dashboardWidgets.addAll(widgets);
    }

    public List<DashboardWidgetDescriptor> allDashboardWidgets() {
        return Collections.unmodifiableList(dashboardWidgets);
    }
}
