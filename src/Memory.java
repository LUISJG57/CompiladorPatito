import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

// Un contexto de memoria de ejecución, indexado por DIRECCIÓN VIRTUAL.
// Se usa para:
//   - la Memoria Global (Data segment: variables globales + temporales de main)
//   - la Tabla de Constantes cargada en memoria
//   - cada Activation Record (StackFrame) de una llamada a función
//
// Los valores almacenados son Integer, Double o String (para letreros).
// Se eligió un HashMap<dirección, valor> porque las direcciones virtuales son
// dispersas (1000, 3000, 5000, ...) y un arreglo desperdiciaría espacio; la
// dirección virtual ES la llave, así que el acceso es O(1).
public class Memory {

    private final Map<Integer, Object> cells = new HashMap<>();

    public Object get(int address) {
        return cells.get(address);     // null si la celda no tiene valor todavía
    }

    public void set(int address, Object value) {
        cells.put(address, value);
    }

    public boolean has(int address) {
        return cells.containsKey(address);
    }

    // Volcado ordenado por dirección (para depurar el estado de la memoria).
    public String dump() {
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(cells).forEach((addr, val) ->
            sb.append("    ").append(addr).append(" = ").append(val).append("\n"));
        return sb.toString();
    }
}
