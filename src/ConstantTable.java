import java.util.LinkedHashMap;
import java.util.Map;

// Tabla de constantes: asigna una dirección virtual única a cada literal.
// Hace dedup: si la misma constante aparece varias veces, reutiliza la dirección.
public class ConstantTable {

    private final VirtualMemory vm;
    // key = "TIPO:literal"  →  dirección
    private final Map<String, Integer> table = new LinkedHashMap<>();
    // dirección → literal (para imprimir la tabla / leyenda)
    private final Map<Integer, String> labels = new LinkedHashMap<>();

    public ConstantTable(VirtualMemory vm) {
        this.vm = vm;
    }

    public int getOrAdd(String literal, SemanticCube.Type type) {
        String key = type + ":" + literal;
        Integer addr = table.get(key);
        if (addr != null) return addr;
        int a = vm.nextConst(type);
        table.put(key, a);
        labels.put(a, literal);
        return a;
    }

    public int getOrAddString(String literal) {
        String key = "STR:" + literal;
        Integer addr = table.get(key);
        if (addr != null) return addr;
        int a = vm.nextConstStr();
        table.put(key, a);
        labels.put(a, literal);
        return a;
    }

    public Map<Integer, String> getLabels() {
        return labels;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        labels.forEach((addr, lit) -> sb.append("    ").append(addr)
                .append(" -> ").append(lit).append("\n"));
        return sb.toString();
    }
}
