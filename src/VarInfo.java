public class VarInfo {
    public final String name;
    public final SemanticCube.Type type;

    public VarInfo(String name, SemanticCube.Type type) {
        this.name = name;
        this.type = type;
    }

    @Override
    public String toString() {
        return name + " : " + type;
    }
}
