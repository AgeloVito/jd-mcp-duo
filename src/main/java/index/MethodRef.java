package index;

public record MethodRef(String owner, String name, String descriptor) {
    public String displayName() {
        return owner.replace('/', '.') + "#" + name + descriptor;
    }

    public boolean isConstructor() {
        return "<init>".equals(name);
    }
}
