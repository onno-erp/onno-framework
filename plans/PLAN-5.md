# Plan #5: Hierarchical Catalogs, Autonumbering, Optimistic Locking

## Context

Polish and production-readiness features that round out the framework for real-world use.

## Scope

### Hierarchical Catalogs
- `isFolder` flag, `parent` (self-referencing `Ref`)
- `CatalogManager.createFolder()`, `getChildren(parentRef)`, `getTree()`
- Schema: `_is_folder BOOLEAN`, `_parent UUID` columns

### Autonumbering
- Automatic `code` generation for catalogs (prefix + sequence)
- Automatic `number` generation for documents (prefix + period-based sequence)
- Sequences table: `_sequences (entity_type, prefix, current_value)`
- Configurable via annotation: `@Catalog(autoNumber = true, codePrefix = "P-")`

### Optimistic Locking
- `version` field on `BaseObject` (integer, incremented on save)
- `UPDATE ... WHERE _id = :id AND _version = :expectedVersion`
- `OptimisticLockException` on conflict

## Verification
1. Create folder hierarchy, query children at each level
2. Create multiple catalog items, verify codes auto-increment
3. Simulate concurrent update, verify optimistic lock exception thrown
