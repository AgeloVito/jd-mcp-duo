package archive;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

public interface InputContainer extends AutoCloseable {

    default List<ResourceEntry> listResources() {
        return List.of();
    }

    default byte[] loadResourceBytes(String entryName) throws IOException {
        return null;
    }

    Path path();

    default Path classpathRoot() {
        return path();
    }

    String kind();

    URI contextUri();

    List<ClassLocation> listClasses(boolean includeInnerClasses);

    ClassLocation resolveClass(String classNameOrInternal);

    byte[] loadClassBytes(String internalName) throws IOException;

    default ClassLocation defaultClass() {
        List<ClassLocation> classes = listClasses(true);
        return classes.size() == 1 ? classes.get(0) : null;
    }

    @Override
    default void close() throws IOException {
    }
}
