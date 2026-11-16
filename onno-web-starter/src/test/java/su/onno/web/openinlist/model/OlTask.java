package su.onno.web.openinlist.model;

import su.onno.annotations.AccessControl;
import su.onno.annotations.Document;
import su.onno.model.DocumentObject;

/** Its view hides "Open in list" and promotes "Duplicate". */
@Document(name = "OlTasks")
@AccessControl(readRoles = {"READER", "WRITER"}, writeRoles = {"WRITER"})
public class OlTask extends DocumentObject {
}
