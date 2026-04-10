package com.onec.posting;

import com.onec.annotations.HandlePosting;
import com.onec.lifecycle.Postable;
import com.onec.metadata.DocumentDescriptor;
import com.onec.metadata.MetadataRegistry;
import com.onec.model.AccumulationRecord;
import com.onec.model.DocumentObject;

import org.jdbi.v3.core.Jdbi;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PostingEngine {

    private final Jdbi jdbi;
    private final MetadataRegistry registry;
    private final Map<Class<?>, RegisterPersistence<?>> persistenceMap;
    private final Map<Class<?>, PostingMethodInfo> methodCache = new ConcurrentHashMap<>();

    public PostingEngine(Jdbi jdbi, MetadataRegistry registry,
                         Map<Class<?>, RegisterPersistence<?>> persistenceMap) {
        this.jdbi = jdbi;
        this.registry = registry;
        this.persistenceMap = persistenceMap;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public void post(DocumentObject document) {
        if (!(document instanceof Postable)) {
            throw new IllegalArgumentException(
                    document.getClass().getName() + " does not implement Postable");
        }

        PostingMethodInfo methodInfo = resolvePostingMethod(document.getClass());
        DocumentDescriptor docDescriptor = registry.getDocumentDescriptor(document.getClass());

        // Create a RegisterMovementCollection for each parameter
        List<RegisterMovementCollection<?>> collections = new ArrayList<>();
        Object[] args = new Object[methodInfo.registerClasses().size()];
        for (int i = 0; i < methodInfo.registerClasses().size(); i++) {
            Class<?> regClass = methodInfo.registerClasses().get(i);
            RegisterMovementCollection<?> collection = new RegisterMovementCollection<>(
                    (Class<? extends AccumulationRecord>) regClass);
            collections.add(collection);
            args[i] = collection;
        }

        // Invoke the posting method
        try {
            methodInfo.method().invoke(document, args);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke @HandlePosting method on " +
                    document.getClass().getName(), e);
        }

        jdbi.useTransaction(handle -> {
            for (RegisterMovementCollection<?> collection : collections) {
                RegisterPersistence persistence = persistenceMap.get(collection.getRegisterClass());
                if (persistence == null) {
                    throw new IllegalStateException(
                            "No persistence registered for " + collection.getRegisterClass().getName());
                }

                persistence.insertRecords(handle, collection.getRecords(),
                        document.getId(), document.getDate());
                persistence.updateTotals(handle, collection.getRecords());
            }

            handle.createUpdate("UPDATE " + docDescriptor.tableName() +
                            " SET _posted = TRUE WHERE _id = :id")
                    .bind("id", document.getId())
                    .execute();
        });

        document.setPosted(true);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public void unpost(DocumentObject document) {
        DocumentDescriptor docDescriptor = registry.getDocumentDescriptor(document.getClass());

        jdbi.useTransaction(handle -> {
            for (RegisterPersistence persistence : persistenceMap.values()) {
                persistence.reverseTotals(handle, document.getId());
                persistence.deactivateRecords(handle, document.getId());
            }

            handle.createUpdate("UPDATE " + docDescriptor.tableName() +
                            " SET _posted = FALSE WHERE _id = :id")
                    .bind("id", document.getId())
                    .execute();
        });

        document.setPosted(false);
    }

    private PostingMethodInfo resolvePostingMethod(Class<?> documentClass) {
        return methodCache.computeIfAbsent(documentClass, this::discoverPostingMethod);
    }

    private PostingMethodInfo discoverPostingMethod(Class<?> documentClass) {
        Method found = null;

        for (Method method : documentClass.getMethods()) {
            if (method.isAnnotationPresent(HandlePosting.class)) {
                if (found != null) {
                    throw new IllegalStateException(
                            documentClass.getName() + " has multiple @HandlePosting methods");
                }
                found = method;
            }
        }

        if (found == null) {
            throw new IllegalStateException(
                    documentClass.getName() + " implements Postable but has no @HandlePosting method");
        }

        List<Class<?>> registerClasses = new ArrayList<>();
        for (Parameter param : found.getParameters()) {
            if (!RegisterMovementCollection.class.isAssignableFrom(param.getType())) {
                throw new IllegalStateException(
                        "@HandlePosting parameters must be RegisterMovementCollection<T>, found: " +
                                param.getType().getName() + " in " + documentClass.getName());
            }

            Type paramType = param.getParameterizedType();
            if (!(paramType instanceof ParameterizedType pt)) {
                throw new IllegalStateException(
                        "@HandlePosting parameter must have a generic type (RegisterMovementCollection<T>)");
            }

            Type typeArg = pt.getActualTypeArguments()[0];
            if (!(typeArg instanceof Class<?> regClass)) {
                throw new IllegalStateException(
                        "@HandlePosting generic type must be a concrete class, found: " + typeArg);
            }

            if (!AccumulationRecord.class.isAssignableFrom(regClass)) {
                throw new IllegalStateException(
                        regClass.getName() + " is not an AccumulationRecord");
            }

            registerClasses.add(regClass);
        }

        return new PostingMethodInfo(found, registerClasses);
    }

    private record PostingMethodInfo(Method method, List<Class<?>> registerClasses) {
    }
}
