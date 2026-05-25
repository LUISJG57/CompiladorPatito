// Representa un cuádruplo (op, opIzq, opDer, resultado).
// El "resultado" puede ser un nombre de variable, un temporal (t1, t2, ...),
// o un índice de cuádruplo (para GOTO/GOTOF).
public class Quadruple {
    public final int id;
    public final String op;
    public String leftOp;
    public String rightOp;
    public String result;

    public Quadruple(int id, String op, String leftOp, String rightOp, String result) {
        this.id = id;
        this.op = op;
        this.leftOp = leftOp;
        this.rightOp = rightOp;
        this.result = result;
    }

    @Override
    public String toString() {
        return String.format("%-4d| %-6s | %-8s | %-8s | %-8s",
                id, op,
                leftOp == null ? "_" : leftOp,
                rightOp == null ? "_" : rightOp,
                result == null ? "_" : result);
    }
}
