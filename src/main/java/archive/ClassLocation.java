package archive;

public record ClassLocation(String internalName, String entryName, String displayName, Integer multiReleaseVersion) {
    public boolean isMultiRelease() {
        return multiReleaseVersion != null;
    }
}
