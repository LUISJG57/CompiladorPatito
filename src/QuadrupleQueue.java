import java.util.ArrayList;
import java.util.List;

// Fila (FIFO) de cuádruplos.
// Internamente se usa un ArrayList para poder indexar (necesario para el GOTO/GOTOF).
// El "next index" siempre equivale a size(), el cuádruplo recién emitido vive en size()-1.
public class QuadrupleQueue {
    private final List<Quadruple> quads = new ArrayList<>();

    // Agrega un cuádruplo al final de la fila y devuelve su índice asignado.
    public int enqueue(String op, String leftOp, String rightOp, String result) {
        int id = quads.size();
        quads.add(new Quadruple(id, op, leftOp, rightOp, result));
        return id;
    }

    public Quadruple get(int idx) {
        return quads.get(idx);
    }

    public int size() {
        return quads.size();
    }

    // Patch del campo "resultado" (usado para rellenar destinos de saltos pendientes)
    public void patchResult(int idx, String newResult) {
        quads.get(idx).result = newResult;
    }

    public List<Quadruple> getAll() {
        return quads;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("#   | OP     | OPIZQ    | OPDER    | RESULT\n");
        sb.append("----+--------+----------+----------+---------\n");
        for (Quadruple q : quads) sb.append(q).append("\n");
        return sb.toString();
    }
}
