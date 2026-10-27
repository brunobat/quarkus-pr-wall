package io.quarkus.prreview.pr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.quarkus.prreview.pr.model.ChangedFile;
import io.quarkus.prreview.pr.model.ChangedFile.Kind;

class DiffFilesTest {

    static final String DIFF = """
            diff --git a/core/runtime/src/main/java/io/quarkus/Foo.java b/core/runtime/src/main/java/io/quarkus/Foo.java
            index 1111111..2222222 100644
            --- a/core/runtime/src/main/java/io/quarkus/Foo.java
            +++ b/core/runtime/src/main/java/io/quarkus/Foo.java
            @@ -1,3 +1,3 @@
             class Foo {
            -    int x = 1;
            +    int x = 2;
             }
            diff --git a/core/runtime/src/test/java/io/quarkus/FooTest.java b/core/runtime/src/test/java/io/quarkus/FooTest.java
            new file mode 100644
            index 0000000..3333333
            --- /dev/null
            +++ b/core/runtime/src/test/java/io/quarkus/FooTest.java
            @@ -0,0 +1,2 @@
            +class FooTest {
            +}
            diff --git a/core/runtime/src/main/java/io/quarkus/Old.java b/core/runtime/src/main/java/io/quarkus/Old.java
            deleted file mode 100644
            index 4444444..0000000
            --- a/core/runtime/src/main/java/io/quarkus/Old.java
            +++ /dev/null
            @@ -1,2 +0,0 @@
            -class Old {
            -}
            diff --git a/core/runtime/src/main/java/io/quarkus/A.java b/core/runtime/src/main/java/io/quarkus/B.java
            similarity index 90%
            rename from core/runtime/src/main/java/io/quarkus/A.java
            rename to core/runtime/src/main/java/io/quarkus/B.java
            index 5555555..6666666 100644
            --- a/core/runtime/src/main/java/io/quarkus/A.java
            +++ b/core/runtime/src/main/java/io/quarkus/B.java
            @@ -1,2 +1,2 @@
            -class A {
            +class B {
             }
            diff --git a/docs/src/main/asciidoc/moved.adoc b/docs/src/main/asciidoc/renamed.adoc
            similarity index 100%
            rename from docs/src/main/asciidoc/moved.adoc
            rename to docs/src/main/asciidoc/renamed.adoc
            diff --git a/docs/src/main/asciidoc/images/logo.png b/docs/src/main/asciidoc/images/logo.png
            index 7777777..8888888 100644
            Binary files a/docs/src/main/asciidoc/images/logo.png and b/docs/src/main/asciidoc/images/logo.png differ
            diff --git a/docs/src/main/asciidoc/arc.adoc b/docs/src/main/asciidoc/arc.adoc
            index 9999999..aaaaaaa 100644
            --- a/docs/src/main/asciidoc/arc.adoc
            +++ b/docs/src/main/asciidoc/arc.adoc
            @@ -1 +1 @@
            -= ArC
            += ArC guide
            diff --git a/docs/new image.png b/docs/new image.png
            new file mode 100644
            index 0000000..bbbbbbb
            Binary files /dev/null and b/docs/new image.png differ
            """;

    @Test
    void listsEveryChangedFileWithItsKind() {
        List<ChangedFile> files = DiffFiles.parse(DIFF);

        assertEquals(List.of(
                new ChangedFile("core/runtime/src/main/java/io/quarkus/Foo.java", Kind.MODIFIED, null, false),
                new ChangedFile("core/runtime/src/test/java/io/quarkus/FooTest.java", Kind.ADDED, null, false),
                new ChangedFile("core/runtime/src/main/java/io/quarkus/Old.java", Kind.DELETED, null, false),
                new ChangedFile("core/runtime/src/main/java/io/quarkus/B.java", Kind.RENAMED,
                        "core/runtime/src/main/java/io/quarkus/A.java", false),
                new ChangedFile("docs/src/main/asciidoc/renamed.adoc", Kind.RENAMED,
                        "docs/src/main/asciidoc/moved.adoc", false),
                new ChangedFile("docs/src/main/asciidoc/images/logo.png", Kind.MODIFIED, null, true),
                new ChangedFile("docs/src/main/asciidoc/arc.adoc", Kind.MODIFIED, null, false),
                new ChangedFile("docs/new image.png", Kind.ADDED, null, true)), files);
    }

    @Test
    void hunkLinesThatLookLikeHeadersAreIgnored() {
        String diff = """
                diff --git a/README.md b/README.md
                index 1111111..2222222 100644
                --- a/README.md
                +++ b/README.md
                @@ -1,2 +1,2 @@
                -rename from nowhere
                +new file mode is not a header here
                """;

        assertEquals(List.of(new ChangedFile("README.md", Kind.MODIFIED, null, false)), DiffFiles.parse(diff));
    }

    @Test
    void quotedPathsAreUnquoted() {
        String diff = """
                diff --git "a/docs/say \\"hi\\".txt" "b/docs/say \\"hi\\".txt"
                index 1111111..2222222 100644
                --- "a/docs/say \\"hi\\".txt"
                +++ "b/docs/say \\"hi\\".txt"
                @@ -1 +1 @@
                -a
                +b
                """;

        assertEquals(List.of(new ChangedFile("docs/say \"hi\".txt", Kind.MODIFIED, null, false)), DiffFiles.parse(diff));
    }

    @Test
    void renameToAQuotedPathIsSplitAndDecoded() {
        // Git quotes each path on its own: here only the new, non-ASCII one, as octal UTF-8 bytes.
        String diff = """
                diff --git a/docs/plain.txt "b/docs/caf\\303\\251.txt"
                similarity index 100%
                rename from docs/plain.txt
                rename to "docs/caf\\303\\251.txt"
                """;

        assertEquals(List.of(new ChangedFile("docs/café.txt", Kind.RENAMED, "docs/plain.txt", false)),
                DiffFiles.parse(diff));
        assertEquals(List.of("docs/plain.txt", "docs/café.txt"),
                List.of(DiffFiles.splitHeaderPaths("a/docs/plain.txt \"b/docs/caf\\303\\251.txt\"")));
    }

    @Test
    void unquoteDecodesOctalUtf8AndEscapes() {
        assertEquals("caf\u00e9 \"x\"\ty\\z", DiffFiles.unquote("\"caf\\303\\251 \\\"x\\\"\\ty\\\\z\""));
        assertEquals("plain", DiffFiles.unquote("plain"));
    }

    @Test
    void unexpectedHeaderShapeDoesNotFail() {
        assertEquals(List.of(new ChangedFile("weird", Kind.MODIFIED, null, false)),
                DiffFiles.parse("diff --git weird\n"));
    }

    @Test
    void emptyDiffHasNoFiles() {
        assertEquals(List.of(), DiffFiles.parse(""));
    }

    @Test
    void describesKinds() {
        assertEquals("modified", new ChangedFile("a", Kind.MODIFIED, null, false).describe());
        assertEquals("renamed from x/Old.java", new ChangedFile("x/New.java", Kind.RENAMED, "x/Old.java", false).describe());
        assertEquals("added, binary", new ChangedFile("logo.png", Kind.ADDED, null, true).describe());
    }
}
