package index;

public record TypeReferenceHit(String sourceOwner,
                               String sourceMemberName,
                               String sourceMemberDescriptor,
                               String targetType,
                               String kind) {
    public boolean isMemberReference() {
        return sourceMemberName != null;
    }
}
