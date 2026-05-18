import java.util.HashMap;
import java.util.Map;

public class SemanticCube {

    public enum Type {
        ENTERO, FLOTANTE, ERROR
    }

    public enum Operator {
        MAS, MENOS, POR, ENTRE,
        MENORQUE, MAYORQUE, DIFERENTE, IGUAL
    }

    // key: "tipoIzq,operador,tipoDer"  →  tipoResultado
    private static final Map<String, Type> cube = new HashMap<>();

    static {
        for (Operator op : Operator.values()) {
            boolean isRelacional = op == Operator.MENORQUE || op == Operator.MAYORQUE
                    || op == Operator.DIFERENTE || op == Operator.IGUAL;

            // entero op entero
            cube.put(key(Type.ENTERO, op, Type.ENTERO),
                    isRelacional ? Type.ENTERO : Type.ENTERO);

            // entero op flotante
            cube.put(key(Type.ENTERO, op, Type.FLOTANTE),
                    isRelacional ? Type.ENTERO : Type.FLOTANTE);

            // flotante op entero
            cube.put(key(Type.FLOTANTE, op, Type.ENTERO),
                    isRelacional ? Type.ENTERO : Type.FLOTANTE);

            // flotante op flotante
            cube.put(key(Type.FLOTANTE, op, Type.FLOTANTE),
                    isRelacional ? Type.ENTERO : Type.FLOTANTE);
        }
    }

    private static String key(Type left, Operator op, Type right) {
        return left + "," + op + "," + right;
    }

    public static Type getResultType(Type left, Operator op, Type right) {
        return cube.getOrDefault(key(left, op, right), Type.ERROR);
    }
}
