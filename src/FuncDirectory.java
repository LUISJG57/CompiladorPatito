import java.util.LinkedHashMap;
import java.util.Map;

public class FuncDirectory {
    private final Map<String, FuncInfo> directory = new LinkedHashMap<>();

    // Retorna true si se agregó, false si la función ya estaba declarada
    public boolean addFunc(String name, SemanticCube.Type returnType) {
        if (directory.containsKey(name)) return false;
        directory.put(name, new FuncInfo(name, returnType));
        return true;
    }

    public boolean contains(String name) {
        return directory.containsKey(name);
    }

    public FuncInfo getFunc(String name) {
        return directory.get(name);
    }

    public Map<String, FuncInfo> getAll() {
        return directory;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("=== DIRECTORIO DE FUNCIONES ===\n");
        directory.forEach((k, v) -> sb.append(v).append("\n"));
        return sb.toString();
    }
}
