package su.onno.web.uimodel.model;

import su.onno.annotations.AccessControl;
import su.onno.annotations.Document;
import su.onno.model.DocumentObject;

/** A document readable by READER and WRITER; no explicit title or context. */
@Document(name = "UmOrders")
@AccessControl(readRoles = {"READER", "WRITER"}, writeRoles = {"WRITER"})
public class UmOrder extends DocumentObject {
}
