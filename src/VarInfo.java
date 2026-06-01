public class VarInfo {
    public final String name;
    public final SemanticCube.Type type;
    public final int address;   // dirección virtual (entrega 4)

    public VarInfo(String name, SemanticCube.Type type, int address) {
        this.name = name;
        this.type = type;
        this.address = address;
    }

    @Override
    public String toString() {
        return name + " : " + type + " @" + address;
    }
}
