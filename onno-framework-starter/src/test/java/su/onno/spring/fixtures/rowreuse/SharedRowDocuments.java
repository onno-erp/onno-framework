package su.onno.spring.fixtures.rowreuse;

import su.onno.annotations.Document;
import su.onno.annotations.TabularSection;
import su.onno.model.DocumentObject;
import su.onno.model.TabularSectionRow;

import java.util.ArrayList;
import java.util.List;

public final class SharedRowDocuments {

    private SharedRowDocuments() {
    }

    public static class SharedLine extends TabularSectionRow {
    }

    @Document(name = "FirstSharedRowDocuments")
    public static class FirstDocument extends DocumentObject {
        @TabularSection(name = "firstLines")
        private List<SharedLine> firstLines = new ArrayList<>();
    }

    @Document(name = "SecondSharedRowDocuments")
    public static class SecondDocument extends DocumentObject {
        @TabularSection(name = "secondLines")
        private List<SharedLine> secondLines = new ArrayList<>();
    }
}
