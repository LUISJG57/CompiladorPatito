import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.antlr.v4.runtime.ParserRuleContext;

public class PatitoSemanticListener extends PatitoBaseListener {

    private final FuncDirectory funcDirectory = new FuncDirectory();
    private final VarTable globalVarTable = new VarTable();
    private final List<String> errors = new ArrayList<>();

    // null = alcance global (vars del programa)
    // non-null = dentro de una declaración de función
    private String currentFunc = null;

    // ── Estructuras para generación de cuádruplos ───────────────────────────
    // PILA de operandos: ahora contiene DIRECCIONES VIRTUALES (entrega 4).
    private final Deque<Integer> operandStack = new ArrayDeque<>();
    // PILA de tipos: tipo de cada operando (misma posición que operandStack).
    private final Deque<SemanticCube.Type> typeStack = new ArrayDeque<>();
    // PILA de operadores: "+ - * / < > == !=" y "(" como fondo falso.
    private final Deque<String> operatorStack = new ArrayDeque<>();
    // PILA de saltos: índices de cuádruplos pendientes de FILL (GOTO/GOTOF) y retornos (while).
    private final Deque<Integer> jumpStack = new ArrayDeque<>();
    // PILA de llamadas: contexto de cada llamada a función en curso.
    private final Deque<CallContext> callStack = new ArrayDeque<>();
    // FILA de cuádruplos.
    private final QuadrupleQueue quads = new QuadrupleQueue();

    // ── Memoria virtual y constantes (entrega 4) ────────────────────────────
    private final VirtualMemory vm = new VirtualMemory();
    private final ConstantTable constants = new ConstantTable(vm);

    // Índice del GOTO inicial (cuádruplo 0) que salta a main.
    private int mainGotoIdx = -1;

    private static final String FONDO_FALSO = "(";

    // Contexto de una llamada en curso (para validar firma y contar parámetros).
    private static class CallContext {
        final FuncInfo func;  // null si la función no existe
        int k = 1;            // contador de parámetro actual (1-based)
        CallContext(FuncInfo func) { this.func = func; }
    }

    // ============================================================
    // SECCIÓN A — Programa, funciones y variables
    // ============================================================

    // Cuádruplo 0: GOTO a main (se rellena al entrar al cuerpo principal).
    @Override
    public void enterPrograma(PatitoParser.ProgramaContext ctx) {
        mainGotoIdx = quads.enqueue("GOTO", "_", "_", "?");
    }

    // Inicio del cuerpo main: rellenar el GOTO inicial con la dirección actual.
    @Override
    public void enterCuerpo(PatitoParser.CuerpoContext ctx) {
        if (ctx.getParent() instanceof PatitoParser.ProgramaContext) {
            quads.patchResult(mainGotoIdx, String.valueOf(quads.size()));
        }
    }

    // Punto neurálgico: entrada a función -> registrar, abrir alcance local, marcar startQuad
    @Override
    public void enterFuncs(PatitoParser.FuncsContext ctx) {
        String funcName = ctx.ID().getText();
        SemanticCube.Type retType = parseReturnType(ctx.tipoOpcional());

        if (!funcDirectory.addFunc(funcName, retType)) {
            errors.add("SEMÁNTICO [línea " + ctx.ID().getSymbol().getLine()
                + "]: Función doblemente declarada '" + funcName + "'");
        }
        currentFunc = funcName;
        vm.pushLocalTempScope();                 // nuevo Activation Record: reiniciar local+temp
        if (funcDirectory.contains(funcName)) {
            funcDirectory.getFunc(funcName).startQuad = quads.size();   // CONT
        }
    }

    // Punto neurálgico: salida de función -> guardar recursos, ENDFUNC, cerrar alcance
    @Override
    public void exitFuncs(PatitoParser.FuncsContext ctx) {
        if (currentFunc != null && funcDirectory.contains(currentFunc)) {
            int[] c = vm.currentLocalTempCounts();   // [locInt, locFlt, tmpInt, tmpFlt]
            funcDirectory.getFunc(currentFunc).setResources(c[0], c[1], c[2], c[3]);
        }
        quads.enqueue("ENDFUNC", "_", "_", "_");
        vm.popLocalTempScope();
        currentFunc = null;
    }

    // Punto neurálgico: primer parámetro -> dirección local + registro en firma
    @Override
    public void enterParams(PatitoParser.ParamsContext ctx) {
        if (currentFunc == null || !funcDirectory.contains(currentFunc)) return;
        FuncInfo func = funcDirectory.getFunc(currentFunc);
        String paramName = ctx.ID().getText();
        SemanticCube.Type paramType = parseType(ctx.tipo());
        func.addParam(paramName, paramType, vm.nextLocal(paramType));
    }

    // Punto neurálgico: parámetros adicionales (COMA ID : tipo ...)
    @Override
    public void enterParamsP(PatitoParser.ParamsPContext ctx) {
        if (ctx.ID() == null) return; // alternativa vacía
        if (currentFunc == null || !funcDirectory.contains(currentFunc)) return;
        FuncInfo func = funcDirectory.getFunc(currentFunc);
        String paramName = ctx.ID().getText();
        SemanticCube.Type paramType = parseType(ctx.tipo());
        func.addParam(paramName, paramType, vm.nextLocal(paramType));
    }

    // Punto neurálgico: cada línea de declaración id1, id2 : tipo ;  (globales o locales)
    @Override
    public void enterListDecl(PatitoParser.ListDeclContext ctx) {
        SemanticCube.Type type = parseType(ctx.tipo());
        List<String> ids = collectIds(ctx.listId());
        VarTable target = resolveVarTable();
        boolean local = (currentFunc != null && funcDirectory.contains(currentFunc));

        for (String id : ids) {
            int addr = local ? vm.nextLocal(type) : vm.nextGlobal(type);
            if (!target.addVar(id, type, addr)) {
                errors.add("SEMÁNTICO [línea " + ctx.tipo().getStart().getLine()
                    + "]: Variable doblemente declarada '" + id + "'");
            }
        }
    }

    // ============================================================
    // SECCIÓN B — Expresiones (operandos -> direcciones)
    // ============================================================

    // PN-A1: identificador o constante -> push DIRECCIÓN + tipo
    @Override
    public void exitFactorBase(PatitoParser.FactorBaseContext ctx) {
        if (ctx.ID() != null) {
            String name = ctx.ID().getText();
            VarInfo v = lookupVar(name);
            if (v == null) {
                errors.add("SEMÁNTICO [línea " + ctx.ID().getSymbol().getLine()
                    + "]: Variable no declarada '" + name + "'");
                operandStack.push(-1);
                typeStack.push(SemanticCube.Type.ERROR);
            } else {
                operandStack.push(v.address);
                typeStack.push(v.type);
            }
        } else { // constante
            PatitoParser.CteContext c = ctx.cte();
            if (c.CTE_INT() != null) {
                operandStack.push(constants.getOrAdd(c.CTE_INT().getText(), SemanticCube.Type.ENTERO));
                typeStack.push(SemanticCube.Type.ENTERO);
            } else {
                operandStack.push(constants.getOrAdd(c.CTE_FLOAT().getText(), SemanticCube.Type.FLOTANTE));
                typeStack.push(SemanticCube.Type.FLOTANTE);
            }
        }
    }

    // PN-B1: "(" -> fondo falso
    @Override
    public void enterFactor(PatitoParser.FactorContext ctx) {
        if (ctx.PARENTESISIZQ() != null) {
            operatorStack.push(FONDO_FALSO);
        }
    }

    // PN-B2 / PN-C1: cerrar factor -> quitar fondo falso o aplicar signo, y resolver *,/
    @Override
    public void exitFactor(PatitoParser.FactorContext ctx) {
        if (ctx.PARENTESISIZQ() != null) {
            if (!operatorStack.isEmpty() && FONDO_FALSO.equals(operatorStack.peek())) {
                operatorStack.pop();
            }
        } else if (ctx.factorBase() != null) {
            // signo unario opcional (sólo MENOS amerita cuádruplo NEG)
            if (ctx.signoOpcional() != null && ctx.signoOpcional().MENOS() != null) {
                int operand = operandStack.pop();
                SemanticCube.Type t = typeStack.pop();
                int temp = vm.nextTemp(t);
                quads.enqueue("NEG", String.valueOf(operand), "_", String.valueOf(temp));
                operandStack.push(temp);
                typeStack.push(t);
            }
        }
        if (topOperatorIsOneOf("*", "/")) {
            generateBinaryQuadruple();
        }
    }

    // PN-C2: * o / -> push a la pila de operadores
    @Override
    public void enterTerminoP(PatitoParser.TerminoPContext ctx) {
        if (ctx.POR() != null) operatorStack.push("*");
        else if (ctx.ENTRE() != null) operatorStack.push("/");
    }

    // PN-D1: + o - -> push a la pila de operadores
    @Override
    public void enterExpP(PatitoParser.ExpPContext ctx) {
        if (ctx.MAS() != null) operatorStack.push("+");
        else if (ctx.MENOS() != null) operatorStack.push("-");
    }

    // PN-D2: cerrar termino -> resolver + o - pendiente
    @Override
    public void exitTermino(PatitoParser.TerminoContext ctx) {
        if (topOperatorIsOneOf("+", "-")) {
            generateBinaryQuadruple();
        }
    }

    // PN-E1: operador relacional -> push
    @Override
    public void exitOpRel(PatitoParser.OpRelContext ctx) {
        if (ctx.MENORQUE() != null) operatorStack.push("<");
        else if (ctx.MAYORQUE() != null) operatorStack.push(">");
        else if (ctx.IGUAL() != null) operatorStack.push("==");
        else if (ctx.DIFERENTE() != null) operatorStack.push("!=");
    }

    // PN-E2: cerrar expresion -> resolver relacional y despachar según el contexto (padre)
    @Override
    public void exitExpresion(PatitoParser.ExpresionContext ctx) {
        if (topOperatorIsOneOf("<", ">", "==", "!=")) {
            generateBinaryQuadruple();
        }

        ParserRuleContext parent = ctx.getParent();
        if (parent instanceof PatitoParser.CondicionContext
            || parent instanceof PatitoParser.CicloContext) {
            // condición de un si/mientras -> GOTOF
            generateConditionalGoToF(ctx.getStart().getLine());
        } else if (parent instanceof PatitoParser.ArgsContext
                || parent instanceof PatitoParser.ArgsPContext) {
            // argumento de una llamada -> PARAMETER
            processArgument(ctx.getStart().getLine());
        }
        // otros padres (asigna, elemImp, factor): el resultado queda en la pila
    }

    // ============================================================
    // SECCIÓN C — Estatutos lineales
    // ============================================================

    // PN-G1: asignación -> "=" con direcciones
    @Override
    public void exitAsigna(PatitoParser.AsignaContext ctx) {
        if (operandStack.isEmpty()) return;

        int exprResult = operandStack.pop();
        SemanticCube.Type exprType = typeStack.pop();

        String target = ctx.ID().getText();
        VarInfo tv = lookupVar(target);
        if (tv == null) {
            errors.add("SEMÁNTICO [línea " + ctx.ID().getSymbol().getLine()
                + "]: Variable no declarada '" + target + "'");
            return;
        }
        if (!isAssignable(tv.type, exprType)) {
            errors.add("SEMÁNTICO [línea " + ctx.ID().getSymbol().getLine()
                + "]: Tipos incompatibles en asignación a '" + target
                + "' (" + tv.type + " = " + exprType + ")");
            return;
        }
        quads.enqueue("=", String.valueOf(exprResult), "_", String.valueOf(tv.address));
    }

    // PN-H1: cada elemento de escribe -> PRINT
    @Override
    public void exitElemImp(PatitoParser.ElemImpContext ctx) {
        if (ctx.LETRERO() != null) {
            int addr = constants.getOrAddString(ctx.LETRERO().getText());
            quads.enqueue("PRINT", String.valueOf(addr), "_", "_");
        } else {
            if (operandStack.isEmpty()) return;
            int val = operandStack.pop();
            typeStack.pop();
            quads.enqueue("PRINT", String.valueOf(val), "_", "_");
        }
    }

    // ============================================================
    // SECCIÓN D — Estatutos no-lineales (si/sino, mientras)
    // ============================================================

    // PN-F2: entrar a sinoOpcional con SINO -> GOTO + FILL del GOTOF
    @Override
    public void enterSinoOpcional(PatitoParser.SinoOpcionalContext ctx) {
        if (ctx.SINO() == null) return;
        int gotoIdx = quads.enqueue("GOTO", "_", "_", "?");
        int pendingGotoF = jumpStack.pop();
        quads.patchResult(pendingGotoF, String.valueOf(quads.size()));
        jumpStack.push(gotoIdx);
    }

    // PN-F3: cerrar condicion -> FILL del salto pendiente
    @Override
    public void exitCondicion(PatitoParser.CondicionContext ctx) {
        if (jumpStack.isEmpty()) return;
        int pending = jumpStack.pop();
        quads.patchResult(pending, String.valueOf(quads.size()));
    }

    // PN-I1: entrar al ciclo -> recordar inicio de la condición
    @Override
    public void enterCiclo(PatitoParser.CicloContext ctx) {
        jumpStack.push(quads.size());
    }

    // PN-I2: cerrar ciclo -> GOTO al inicio + FILL del GOTOF
    @Override
    public void exitCiclo(PatitoParser.CicloContext ctx) {
        if (jumpStack.size() < 2) return;
        int pendingGotoF = jumpStack.pop();
        int condStart    = jumpStack.pop();
        quads.enqueue("GOTO", "_", "_", String.valueOf(condStart));
        quads.patchResult(pendingGotoF, String.valueOf(quads.size()));
    }

    // ============================================================
    // SECCIÓN E — Funciones: llamada (ERA / PARAMETER / GOSUB)
    // ============================================================

    // PN-J1: entrar a llamada -> verificar existencia, ERA, abrir contexto de llamada
    @Override
    public void enterLlamada(PatitoParser.LlamadaContext ctx) {
        String funcName = ctx.ID().getText();

        // Patito sólo tiene funciones nula: no pueden usarse dentro de una expresión.
        if (ctx.getParent() instanceof PatitoParser.FactorContext) {
            errors.add("SEMÁNTICO [línea " + ctx.ID().getSymbol().getLine()
                + "]: No se puede usar una función dentro de una expresión "
                + "(Patito sólo tiene funciones nula): '" + funcName + "'");
        }

        FuncInfo f = funcDirectory.getFunc(funcName);
        if (f == null) {
            errors.add("SEMÁNTICO [línea " + ctx.ID().getSymbol().getLine()
                + "]: Función no declarada '" + funcName + "'");
            callStack.push(new CallContext(null)); // mantener balance de la pila
            return;
        }
        quads.enqueue("ERA", funcName, "_", String.valueOf(f.eraSize));
        callStack.push(new CallContext(f));
    }

    // PN-J3: argumento -> validar tipo vs firma + PARAMETER
    private void processArgument(int line) {
        if (callStack.isEmpty() || operandStack.isEmpty()) return;
        CallContext call = callStack.peek();

        int argAddr = operandStack.pop();
        SemanticCube.Type argType = typeStack.pop();

        if (call.func != null) {
            if (call.k <= call.func.params.size()) {
                SemanticCube.Type paramType = call.func.params.get(call.k - 1).type;
                if (!isAssignable(paramType, argType)) {
                    errors.add("SEMÁNTICO [línea " + line + "]: Tipo del argumento #" + call.k
                        + " incompatible en llamada a '" + call.func.name + "' (esperaba "
                        + paramType + ", recibió " + argType + ")");
                }
            } else {
                errors.add("SEMÁNTICO [línea " + line + "]: Demasiados argumentos en llamada a '"
                    + call.func.name + "'");
            }
        }
        quads.enqueue("PARAMETER", String.valueOf(argAddr), "_", String.valueOf(call.k));
        call.k++;
    }

    // PN-J6: cerrar llamada -> validar aridad + GOSUB
    @Override
    public void exitLlamada(PatitoParser.LlamadaContext ctx) {
        if (callStack.isEmpty()) return;
        CallContext call = callStack.pop();
        if (call.func == null) return; // ya se reportó "no declarada"

        int argsGiven = call.k - 1;
        int expected  = call.func.params.size();
        if (argsGiven != expected) {
            errors.add("SEMÁNTICO [línea " + ctx.ID().getSymbol().getLine()
                + "]: Número de argumentos incorrecto en '" + call.func.name
                + "' (esperaba " + expected + ", recibió " + argsGiven + ")");
        }
        quads.enqueue("GOSUB", call.func.name, "_", String.valueOf(call.func.startQuad));
    }

    // ============================================================
    // SECCIÓN F — Helpers
    // ============================================================

    private void generateBinaryQuadruple() {
        String op = operatorStack.pop();
        int right = operandStack.pop();
        int left  = operandStack.pop();
        SemanticCube.Type rightT = typeStack.pop();
        SemanticCube.Type leftT  = typeStack.pop();

        SemanticCube.Operator opEnum = mapOperator(op);
        SemanticCube.Type resultType = SemanticCube.getResultType(leftT, opEnum, rightT);
        if (resultType == SemanticCube.Type.ERROR) {
            errors.add("SEMÁNTICO: Tipos incompatibles para operador '" + op
                + "' entre " + leftT + " y " + rightT);
            resultType = SemanticCube.Type.ENTERO; // continuar para no romper la pila
        }

        int temp = vm.nextTemp(resultType);
        quads.enqueue(op, String.valueOf(left), String.valueOf(right), String.valueOf(temp));
        operandStack.push(temp);
        typeStack.push(resultType);
    }

    private void generateConditionalGoToF(int line) {
        if (operandStack.isEmpty()) return;
        int cond = operandStack.pop();
        SemanticCube.Type t = typeStack.pop();
        if (t != SemanticCube.Type.ENTERO && t != SemanticCube.Type.ERROR) {
            errors.add("SEMÁNTICO [línea " + line
                + "]: La condición debe ser de tipo entero, se obtuvo " + t);
        }
        int idx = quads.enqueue("GOTOF", String.valueOf(cond), "_", "?");
        jumpStack.push(idx);
    }

    private boolean topOperatorIsOneOf(String... ops) {
        if (operatorStack.isEmpty()) return false;
        String top = operatorStack.peek();
        for (String o : ops) if (o.equals(top)) return true;
        return false;
    }

    private SemanticCube.Operator mapOperator(String op) {
        switch (op) {
            case "+":  return SemanticCube.Operator.MAS;
            case "-":  return SemanticCube.Operator.MENOS;
            case "*":  return SemanticCube.Operator.POR;
            case "/":  return SemanticCube.Operator.ENTRE;
            case "<":  return SemanticCube.Operator.MENORQUE;
            case ">":  return SemanticCube.Operator.MAYORQUE;
            case "==": return SemanticCube.Operator.IGUAL;
            case "!=": return SemanticCube.Operator.DIFERENTE;
            default:   return null;
        }
    }

    // Patito sólo tiene entero y flotante. Se permite conversión implícita entre ambos.
    private boolean isAssignable(SemanticCube.Type target, SemanticCube.Type expr) {
        if (target == SemanticCube.Type.ERROR || expr == SemanticCube.Type.ERROR) return true;
        return target == expr
            || (target == SemanticCube.Type.FLOTANTE && expr == SemanticCube.Type.ENTERO)
            || (target == SemanticCube.Type.ENTERO   && expr == SemanticCube.Type.FLOTANTE);
    }

    private VarTable resolveVarTable() {
        if (currentFunc != null && funcDirectory.contains(currentFunc)) {
            return funcDirectory.getFunc(currentFunc).localVars;
        }
        return globalVarTable;
    }

    // Busca primero en locales de la función actual, luego en globales.
    private VarInfo lookupVar(String name) {
        if (currentFunc != null && funcDirectory.contains(currentFunc)) {
            VarInfo v = funcDirectory.getFunc(currentFunc).localVars.getVar(name);
            if (v != null) return v;
        }
        return globalVarTable.getVar(name);
    }

    private List<String> collectIds(PatitoParser.ListIdContext ctx) {
        List<String> ids = new ArrayList<>();
        ids.add(ctx.ID().getText());
        collectIdsComa(ctx.listIdComa(), ids);
        return ids;
    }

    private void collectIdsComa(PatitoParser.ListIdComaContext ctx, List<String> ids) {
        if (ctx == null || ctx.ID() == null) return;
        ids.add(ctx.ID().getText());
        collectIdsComa(ctx.listIdComa(), ids);
    }

    private SemanticCube.Type parseType(PatitoParser.TipoContext ctx) {
        return ctx.ENTERO() != null ? SemanticCube.Type.ENTERO : SemanticCube.Type.FLOTANTE;
    }

    private SemanticCube.Type parseReturnType(PatitoParser.TipoOpcionalContext ctx) {
        if (ctx.NULA() != null) return null;
        return parseType(ctx.tipo());
    }

    // ============================================================
    // SECCIÓN G — Acceso público
    // ============================================================

    public FuncDirectory   getFuncDirectory()  { return funcDirectory; }
    public VarTable        getGlobalVarTable() { return globalVarTable; }
    public List<String>    getErrors()         { return errors; }
    public boolean         hasErrors()         { return !errors.isEmpty(); }
    public QuadrupleQueue  getQuadruples()     { return quads; }
    public ConstantTable   getConstants()      { return constants; }
    public VirtualMemory   getVirtualMemory()  { return vm; }
}
