import java.util.ArrayDeque;
import java.util.Deque;

// Administrador de direcciones virtuales.
// Cada (segmento, tipo) tiene un rango de 1000 direcciones y su propio contador.
// - Global y Constante: únicos para todo el programa, nunca se reinician.
// - Local y Temporal: se guardan y reinician al entrar a cada función (Activation Record)
//   y se restauran al salir (pushLocalTempScope / popLocalTempScope).
public class VirtualMemory {

    // Bases de cada segmento
    public static final int GLOBAL_INT_BASE   = 1000;
    public static final int GLOBAL_FLOAT_BASE = 2000;
    public static final int LOCAL_INT_BASE    = 3000;
    public static final int LOCAL_FLOAT_BASE  = 4000;
    public static final int TEMP_INT_BASE     = 5000;
    public static final int TEMP_FLOAT_BASE   = 6000;
    public static final int CONST_INT_BASE    = 7000;
    public static final int CONST_FLOAT_BASE  = 8000;
    public static final int CONST_STR_BASE    = 9000;
    public static final int SEGMENT_SIZE      = 1000;

    // Contadores
    private int globalInt = GLOBAL_INT_BASE, globalFloat = GLOBAL_FLOAT_BASE;
    private int localInt  = LOCAL_INT_BASE,  localFloat  = LOCAL_FLOAT_BASE;
    private int tempInt   = TEMP_INT_BASE,   tempFloat   = TEMP_FLOAT_BASE;
    private int constInt  = CONST_INT_BASE,  constFloat  = CONST_FLOAT_BASE;
    private int constStr  = CONST_STR_BASE;

    // Pila para guardar/restaurar los contadores local+temp por función
    private final Deque<int[]> savedScopes = new ArrayDeque<>();

    // ── Asignación de direcciones ────────────────────────────────────────────
    public int nextGlobal(SemanticCube.Type t) {
        return t == SemanticCube.Type.ENTERO
            ? check(globalInt++, GLOBAL_INT_BASE, "global entero")
            : check(globalFloat++, GLOBAL_FLOAT_BASE, "global flotante");
    }

    public int nextLocal(SemanticCube.Type t) {
        return t == SemanticCube.Type.ENTERO
            ? check(localInt++, LOCAL_INT_BASE, "local entero")
            : check(localFloat++, LOCAL_FLOAT_BASE, "local flotante");
    }

    public int nextTemp(SemanticCube.Type t) {
        return t == SemanticCube.Type.ENTERO
            ? check(tempInt++, TEMP_INT_BASE, "temporal entero")
            : check(tempFloat++, TEMP_FLOAT_BASE, "temporal flotante");
    }

    public int nextConst(SemanticCube.Type t) {
        return t == SemanticCube.Type.ENTERO
            ? check(constInt++, CONST_INT_BASE, "constante entero")
            : check(constFloat++, CONST_FLOAT_BASE, "constante flotante");
    }

    public int nextConstStr() {
        return check(constStr++, CONST_STR_BASE, "constante letrero");
    }

    private int check(int assigned, int base, String seg) {
        if (assigned >= base + SEGMENT_SIZE)
            throw new RuntimeException("Memoria virtual agotada en segmento " + seg);
        return assigned;
    }

    // ── Alcance local/temporal por función ───────────────────────────────────
    // Guarda los contadores actuales de local+temp y los reinicia a su base.
    public void pushLocalTempScope() {
        savedScopes.push(new int[]{localInt, localFloat, tempInt, tempFloat});
        localInt  = LOCAL_INT_BASE;  localFloat = LOCAL_FLOAT_BASE;
        tempInt   = TEMP_INT_BASE;   tempFloat  = TEMP_FLOAT_BASE;
    }

    // Cantidad de direcciones usadas en el alcance actual (antes de restaurar).
    // [localInt, localFloat, tempInt, tempFloat]
    public int[] currentLocalTempCounts() {
        return new int[]{
            localInt  - LOCAL_INT_BASE,  localFloat - LOCAL_FLOAT_BASE,
            tempInt   - TEMP_INT_BASE,   tempFloat  - TEMP_FLOAT_BASE
        };
    }

    public void popLocalTempScope() {
        int[] s = savedScopes.pop();
        localInt = s[0]; localFloat = s[1]; tempInt = s[2]; tempFloat = s[3];
    }

    // Etiqueta corta del segmento de una dirección (para depuración/leyenda).
    public String segmentOf(int addr) {
        if (addr >= CONST_STR_BASE)    return "cteStr";
        if (addr >= CONST_FLOAT_BASE)  return "cteFlt";
        if (addr >= CONST_INT_BASE)    return "cteInt";
        if (addr >= TEMP_FLOAT_BASE)   return "tmpFlt";
        if (addr >= TEMP_INT_BASE)     return "tmpInt";
        if (addr >= LOCAL_FLOAT_BASE)  return "locFlt";
        if (addr >= LOCAL_INT_BASE)    return "locInt";
        if (addr >= GLOBAL_FLOAT_BASE) return "gblFlt";
        if (addr >= GLOBAL_INT_BASE)   return "gblInt";
        return "?";
    }
}
