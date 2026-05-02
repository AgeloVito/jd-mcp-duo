package index;

public record IndexedField(String owner, String name, String descriptor, int access) {
    public String displayName() {
        return owner.replace('/', '.') + "#" + name;
    }
}
