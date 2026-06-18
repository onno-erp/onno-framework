package su.onno.fixtures;

import su.onno.annotations.Document;

@Document(name = "Bad")
public class NotADocument {
    // Does not extend DocumentObject — should fail scanning
}
