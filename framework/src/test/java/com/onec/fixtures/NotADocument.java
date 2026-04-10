package com.onec.fixtures;

import com.onec.annotations.Document;

@Document(name = "Bad")
public class NotADocument {
    // Does not extend DocumentObject — should fail scanning
}
