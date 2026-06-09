import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

// ─────────────────────────────────────────────────────────────────────────────
// Máquina Virtual de Patito.
// Interpreta el código intermedio (cuádruplos) leído de un archivo OBJ (.pobj).
//
// Componentes (modelo de las láminas "Proceso de Traducción y MV"):
//   - Execution & Control Unit : el switch que interpreta cada código de operación.
//   - Memory Manager           : enruta cada dirección virtual a la memoria que le toca.
//   - I/O                      : la operación PRINT.
//   - Execution Stack          : pila de Activation Records (StackFrames) de funciones.
//
// Mapa de memoria de ejecución:
//   - globalMemory : variables globales (1000-2999) + temporales de main (5000-6999)
//   - constMemory  : constantes (7000-9999), se cargan una sola vez
//   - currentContext : memoria activa para locales/temporales (es globalMemory en main,
//                      o el Activation Record de la función en ejecución)
// ─────────────────────────────────────────────────────────────────────────────
public class VirtualMachine {

    // Un cuádruplo ya cargado del OBJ.
    private static class Quad {
        final String op, left, right, result;
        Quad(String op, String left, String right, String result) {
            this.op = op; this.left = left; this.right = right; this.result = result;
        }
    }

    // Un marco de la pila de ejecución: a qué contexto y a qué IP regresar.
    private static class Frame {
        final Memory caller;
        final int returnIP;
        Frame(Memory caller, int returnIP) { this.caller = caller; this.returnIP = returnIP; }
    }

    private final List<Quad> code = new ArrayList<>();
    private final Memory globalMemory = new Memory();
    private final Memory constMemory  = new Memory();

    private Memory currentContext = globalMemory;          // memoria de locales/temporales activa
    private final Deque<Memory> eraStack = new ArrayDeque<>();   // ARs pendientes (entre ERA y GOSUB)
    private final Deque<Frame>  callStack = new ArrayDeque<>();  // pila de ejecución

    private static final long MAX_STEPS = 50_000_000L;    // tope anti-bucle-infinito

    // ── Carga del OBJ ────────────────────────────────────────────────────────
    public static VirtualMachine load(String objPath) throws IOException {
        VirtualMachine vmRunner = new VirtualMachine();
        vmRunner.parseObj(objPath);
        return vmRunner;
    }

    private void parseObj(String path) throws IOException {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            String section = "";
            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) continue;
                if (line.startsWith("%%")) { section = line; continue; }

                if (section.equals("%%CONSTANTS")) {
                    // addr TIPO valor(resto de la línea)
                    int sp1 = line.indexOf(' ');
                    int sp2 = line.indexOf(' ', sp1 + 1);
                    int addr = Integer.parseInt(line.substring(0, sp1));
                    String type = line.substring(sp1 + 1, sp2);
                    String value = line.substring(sp2 + 1);
                    switch (type) {
                        case "INT":   constMemory.set(addr, Integer.parseInt(value)); break;
                        case "FLOAT": constMemory.set(addr, Double.parseDouble(value)); break;
                        case "STR":   constMemory.set(addr, stripQuotes(unescape(value))); break;
                    }
                } else if (section.equals("%%QUADRUPLES")) {
                    // id op left right result
                    String[] p = line.split("\\s+");
                    code.add(new Quad(p[1], p[2], p[3], p[4]));
                }
            }
        }
    }

    // ── Bucle principal (Execution & Control Unit) ───────────────────────────
    public void run() {
        int ip = 0;
        long steps = 0;
        while (ip >= 0 && ip < code.size()) {
            if (++steps > MAX_STEPS)
                throw new RuntimeException("VM: se excedió el máximo de instrucciones (posible bucle infinito)");

            Quad q = code.get(ip);
            switch (q.op) {
                case "GOTO":
                    ip = Integer.parseInt(q.result);
                    break;

                case "GOTOF":
                    if (toDouble(getVal(addr(q.left))) == 0.0) ip = Integer.parseInt(q.result);
                    else ip++;
                    break;

                case "=": {
                    int dst = addr(q.result);
                    setVal(dst, coerce(getVal(addr(q.left)), dst));
                    ip++;
                    break;
                }

                case "+": case "-": case "*": case "/": {
                    int res = addr(q.result);
                    double a = toDouble(getVal(addr(q.left)));
                    double b = toDouble(getVal(addr(q.right)));
                    double out;
                    switch (q.op) {
                        case "+": out = a + b; break;
                        case "-": out = a - b; break;
                        case "*": out = a * b; break;
                        default:
                            if (b == 0.0) throw new RuntimeException("VM: división entre cero");
                            out = a / b; break;
                    }
                    setVal(res, isFloatAddr(res) ? (Object) out : (Object) (int) out);
                    ip++;
                    break;
                }

                case "<": case ">": case "==": case "!=": {
                    int res = addr(q.result);
                    double a = toDouble(getVal(addr(q.left)));
                    double b = toDouble(getVal(addr(q.right)));
                    boolean r;
                    switch (q.op) {
                        case "<":  r = a < b;  break;
                        case ">":  r = a > b;  break;
                        case "==": r = a == b; break;
                        default:   r = a != b; break;
                    }
                    setVal(res, r ? 1 : 0);   // en Patito el resultado relacional es entero (0/1)
                    ip++;
                    break;
                }

                case "NEG": {
                    int res = addr(q.result);
                    double v = -toDouble(getVal(addr(q.left)));
                    setVal(res, isFloatAddr(res) ? (Object) v : (Object) (int) v);
                    ip++;
                    break;
                }

                case "PRINT": {
                    int a = addr(q.left);
                    Object v = getVal(a);
                    System.out.println(format(v));
                    ip++;
                    break;
                }

                // ── Funciones ────────────────────────────────────────────────
                case "ERA":
                    eraStack.push(new Memory());      // nuevo Activation Record (aún no activo)
                    ip++;
                    break;

                case "PARAMETER": {
                    int paramAddr = addr(q.result);
                    Object v = getVal(addr(q.left));  // se lee del contexto del que llama
                    eraStack.peek().set(paramAddr, coerce(v, paramAddr)); // se escribe en el AR pendiente
                    ip++;
                    break;
                }

                case "GOSUB": {
                    Memory ar = eraStack.pop();
                    callStack.push(new Frame(currentContext, ip + 1));   // a dónde regresar
                    currentContext = ar;                                 // activar el AR
                    ip = Integer.parseInt(q.result);                     // saltar a startQuad
                    break;
                }

                case "ENDFUNC": {
                    Frame f = callStack.pop();
                    currentContext = f.caller;        // restaurar contexto previo
                    ip = f.returnIP;                  // regresar al IP guardado
                    break;
                }

                case "END":
                    return;

                default:
                    throw new RuntimeException("VM: código de operación desconocido '" + q.op + "'");
            }
        }
    }

    // ── Memory Manager: enruta una dirección a la memoria correcta ───────────
    private Memory resolve(int address) {
        if (address >= VirtualMemory.CONST_INT_BASE) return constMemory;     // 7000+  constantes
        if (address >= VirtualMemory.LOCAL_INT_BASE) return currentContext;  // 3000-6999 locales/temporales
        return globalMemory;                                                 // 1000-2999 globales
    }

    private Object getVal(int address) {
        Object v = resolve(address).get(address);
        if (v == null) return isFloatAddr(address) ? (Object) 0.0 : (Object) 0; // no inicializado -> 0
        return v;
    }

    private void setVal(int address, Object value) {
        resolve(address).set(address, value);
    }

    // ── Helpers de tipo según el RANGO de la dirección virtual ───────────────
    private boolean isFloatAddr(int a) {
        return (a >= VirtualMemory.GLOBAL_FLOAT_BASE && a < VirtualMemory.LOCAL_INT_BASE)   // 2000-2999
            || (a >= VirtualMemory.LOCAL_FLOAT_BASE  && a < VirtualMemory.TEMP_INT_BASE)    // 4000-4999
            || (a >= VirtualMemory.TEMP_FLOAT_BASE   && a < VirtualMemory.CONST_INT_BASE)   // 6000-6999
            || (a >= VirtualMemory.CONST_FLOAT_BASE  && a < VirtualMemory.CONST_STR_BASE);  // 8000-8999
    }

    private int addr(String token) {
        return Integer.parseInt(token);
    }

    private double toDouble(Object v) {
        if (v instanceof Integer) return (Integer) v;
        if (v instanceof Double)  return (Double) v;
        return 0.0;
    }

    // Ajusta un valor al tipo de la celda destino (entero<->flotante).
    private Object coerce(Object v, int destAddr) {
        if (isFloatAddr(destAddr)) return toDouble(v);
        return (int) toDouble(v);
    }

    private String format(Object v) {
        return String.valueOf(v);
    }

    // ── Utilidades de texto para constantes ──────────────────────────────────
    private static String stripQuotes(String s) {
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"')
            return s.substring(1, s.length() - 1);
        return s;
    }

    static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
    }

    static String unescape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                if (n == 'n') sb.append('\n');
                else if (n == 'r') sb.append('\r');
                else if (n == '\\') sb.append('\\');
                else sb.append(n);
            } else sb.append(c);
        }
        return sb.toString();
    }
}
