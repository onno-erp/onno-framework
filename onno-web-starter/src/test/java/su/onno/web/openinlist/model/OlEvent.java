package su.onno.web.openinlist.model;

import su.onno.annotations.AccessControl;
import su.onno.annotations.Document;
import su.onno.model.DocumentObject;

/** Default placement for a document. */
@Document(name = "OlEvents")
@AccessControl(readRoles = {"READER", "WRITER"}, writeRoles = {"WRITER"})
public class OlEvent extends DocumentObject {
}
