import java.util.LinkedHashMap;
import java.util.Map;

public class VarTable {
    // LinkedHashMap preserva orden de inserción (útil para imprimir/depurar)
    private final Map<String, VarInfo> table = new LinkedHashMap<>();

    // Retorna true si se agregó, false si ya existía (variable doblemente declarada)
    public boolean addVar(String name, SemanticCube.Type type) {
        if (table.containsKey(name)) return false;
        table.put(name, new VarInfo(name, type));
        return true;
    }

    public boolean contains(String name) {
        return table.containsKey(name);
    }

    public VarInfo getVar(String name) {
        return table.get(name);
    }

    public Map<String, VarInfo> getAll() {
        return table;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        table.forEach((k, v) -> sb.append("    ").append(v).append("\n"));
        return sb.toString();
    }
}
