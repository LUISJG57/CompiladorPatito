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

    // ── ENTREGA 3: estructuras para generación de cuádruplos ──────────────
    // PILA de operandos: contiene nombres de variables, valores de constantes y temporales.
    private final Deque<String> operandStack = new ArrayDeque<>();
    // PILA de tipos: tipo asociado a cada operando en operandStack (misma posición).
    private final Deque<SemanticCube.Type> typeStack = new ArrayDeque<>();
    // PILA de operadores: contiene "+", "-", "*", "/", "<", ">", "==", "!=" y "(" como fondo falso.
    private final Deque<String> operatorStack = new ArrayDeque<>();
    // PILA de saltos: índices de cuádruplos pendientes de patch (GOTO/GOTOF) o de retorno (while).
    private final Deque<Integer> jumpStack = new ArrayDeque<>();
    // FILA de cuádruplos generados.
    private final QuadrupleQueue quads = new QuadrupleQueue();
    // Contador de variables temporales (t1, t2, ...).
    private int tempCount = 0;

    private static final String FONDO_FALSO = "(";

    // ============================================================
    // SECCIÓN A — Manejo de funciones y variables (entrega 2)
    // ============================================================

    // Punto neurálgico 1: entrada a declaración de función -> registrar en directorio, cambiar alcance a local
    @Override
    public void enterFuncs(PatitoParser.FuncsContext ctx) {
        String funcName = ctx.ID().getText();
        SemanticCube.Type retType = parseReturnType(ctx.tipoOpcional());

        if (!funcDirectory.addFunc(funcName, retType)) {
            errors.add("SEMÁNTICO [línea " + ctx.ID().getSymbol().getLine()
                + "]: Función doblemente declarada '" + funcName + "'");
        }
        currentFunc = funcName;
    }

    // Punto neurálgico 2: salida de declaración de función -> regresar al alcance global
    @Override
    public void exitFuncs(PatitoParser.FuncsContext ctx) {
        currentFunc = null;
    }

    // Punto neurálgico 3: primer parámetro
    @Override
    public void enterParams(PatitoParser.ParamsContext ctx) {
        if (currentFunc == null || !funcDirectory.contains(currentFunc)) return;
        FuncInfo func = funcDirectory.getFunc(currentFunc);
        String paramName = ctx.ID().getText();
        SemanticCube.Type paramType = parseType(ctx.tipo());
        func.addParam(paramName, paramType);
    }

    // Punto neurálgico 4: parámetros adicionales (COMA ID : tipo ...)
    @Override
    public void enterParamsP(PatitoParser.ParamsPContext ctx) {
        if (ctx.ID() == null) return; // alternativa vacía
        if (currentFunc == null || !funcDirectory.contains(currentFunc)) return;
        FuncInfo func = funcDirectory.getFunc(currentFunc);
        String paramName = ctx.ID().getText();
        SemanticCube.Type paramType = parseType(ctx.tipo());
        func.addParam(paramName, paramType);
    }

    // Punto neurálgico 5: cada línea de declaración  id1, id2 : tipo ;
    @Override
    public void enterListDecl(PatitoParser.ListDeclContext ctx) {
        SemanticCube.Type type = parseType(ctx.tipo());
        List<String> ids = collectIds(ctx.listId());
        VarTable target = resolveVarTable();

        for (String id : ids) {
            if (!target.addVar(id, type)) {
                errors.add("SEMÁNTICO [línea " + ctx.tipo().getStart().getLine()
                    + "]: Variable doblemente declarada '" + id + "'");
            }
        }
    }

    // ============================================================
    // SECCIÓN B — Generación de cuádruplos (entrega 3)
    // ============================================================

    // ---- Operandos (puntos neurálgicos PN-A) ---------------------------------
    // PN-A1: identificador o constante en factor -> push a pila de operandos y tipos
    @Override
    public void exitFactorBase(PatitoParser.FactorBaseContext ctx) {
        if (ctx.ID() != null) {
            String name = ctx.ID().getText();
            SemanticCube.Type t = lookupVarType(name);
            if (t == null) {
                errors.add("SEMÁNTICO [línea " + ctx.ID().getSymbol().getLine()
                    + "]: Variable no declarada '" + name + "'");
                t = SemanticCube.Type.ERROR;
            }
            operandStack.push(name);
            typeStack.push(t);
        } else { // constante
            PatitoParser.CteContext c = ctx.cte();
            if (c.CTE_INT() != null) {
                operandStack.push(c.CTE_INT().getText());
                typeStack.push(SemanticCube.Type.ENTERO);
            } else {
                operandStack.push(c.CTE_FLOAT().getText());
                typeStack.push(SemanticCube.Type.FLOTANTE);
            }
        }
    }

    // ---- Paréntesis (PN-B) ---------------------------------------------------
    // PN-B1: al entrar a un "(", se mete un fondo falso a la pila de operadores
    @Override
    public void enterFactor(PatitoParser.FactorContext ctx) {
        if (ctx.PARENTESISIZQ() != null) {
            operatorStack.push(FONDO_FALSO);
        }
    }

    // PN-B2 / PN-C1: al salir de un factor:
    //   - si fue "(expresion)", saca el fondo falso
    //   - si fue factorBase, aplica el signo unario si lo hay
    //   - en ambos casos, intenta resolver una operación * o / pendiente
    @Override
    public void exitFactor(PatitoParser.FactorContext ctx) {
        if (ctx.PARENTESISIZQ() != null) {
            if (!operatorStack.isEmpty() && FONDO_FALSO.equals(operatorStack.peek())) {
                operatorStack.pop();
            }
        } else if (ctx.factorBase() != null) {
            // signo unario opcional (solo MENOS amerita cuádruplo NEG)
            if (ctx.signoOpcional() != null && ctx.signoOpcional().MENOS() != null) {
                String operand = operandStack.pop();
                SemanticCube.Type t = typeStack.pop();
                String temp = newTemp();
                quads.enqueue("NEG", operand, "_", temp);
                operandStack.push(temp);
                typeStack.push(t);
            }
        }
        // Tras tener un operando arriba, vemos si hay un *,/ que resolver
        if (topOperatorIsOneOf("*", "/")) {
            generateBinaryQuadruple();
        }
    }

    // ---- Operadores * y / (PN-C) --------------------------------------------
    // PN-C2: al entrar a terminoP, si comienza con * o /, se mete a la pila
    @Override
    public void enterTerminoP(PatitoParser.TerminoPContext ctx) {
        if (ctx.POR() != null) operatorStack.push("*");
        else if (ctx.ENTRE() != null) operatorStack.push("/");
    }

    // ---- Operadores + y - (PN-D) --------------------------------------------
    // PN-D1: al entrar a expP, si comienza con + o -, se mete a la pila
    @Override
    public void enterExpP(PatitoParser.ExpPContext ctx) {
        if (ctx.MAS() != null) operatorStack.push("+");
        else if (ctx.MENOS() != null) operatorStack.push("-");
    }

    // PN-D2: tras cerrar un termino, intentar resolver un + o - pendiente
    @Override
    public void exitTermino(PatitoParser.TerminoContext ctx) {
        if (topOperatorIsOneOf("+", "-")) {
            generateBinaryQuadruple();
        }
    }

    // ---- Operadores relacionales (PN-E) --------------------------------------
    // PN-E1: tras leer un operador relacional, lo metemos a la pila
    @Override
    public void exitOpRel(PatitoParser.OpRelContext ctx) {
        if (ctx.MENORQUE() != null) operatorStack.push("<");
        else if (ctx.MAYORQUE() != null) operatorStack.push(">");
        else if (ctx.IGUAL() != null) operatorStack.push("==");
        else if (ctx.DIFERENTE() != null) operatorStack.push("!=");
    }

    // PN-E2: al cerrar expresion, si hay un relacional arriba, lo resolvemos
    // y luego, según el padre, generamos el GOTOF de un si/mientras.
    @Override
    public void exitExpresion(PatitoParser.ExpresionContext ctx) {
        if (topOperatorIsOneOf("<", ">", "==", "!=")) {
            generateBinaryQuadruple();
        }

        // PN-F1: si la expresion es la condición de un si o un mientras, generar GOTOF
        ParserRuleContext parent = ctx.getParent();
        if (parent instanceof PatitoParser.CondicionContext
            || parent instanceof PatitoParser.CicloContext) {
            generateConditionalGoToF(ctx.getStart().getLine());
        }
    }

    // ---- Asignación (PN-G) ---------------------------------------------------
    // PN-G1: al cerrar la asignación, sacamos el resultado de expresion y emitimos "="
    @Override
    public void exitAsigna(PatitoParser.AsignaContext ctx) {
        if (operandStack.isEmpty()) return; // expresion falló (error semántico previo)

        String exprResult = operandStack.pop();
        SemanticCube.Type exprType = typeStack.pop();

        String target = ctx.ID().getText();
        SemanticCube.Type targetType = lookupVarType(target);
        if (targetType == null) {
            errors.add("SEMÁNTICO [línea " + ctx.ID().getSymbol().getLine()
                + "]: Variable no declarada '" + target + "'");
            return;
        }

        // Verificación de tipos: el cubo se consulta usando "=" como operación virtual
        if (!isAssignable(targetType, exprType)) {
            errors.add("SEMÁNTICO [línea " + ctx.ID().getSymbol().getLine()
                + "]: Tipos incompatibles en asignación a '" + target
                + "' (" + targetType + " = " + exprType + ")");
            return;
        }

        quads.enqueue("=", exprResult, "_", target);
    }

    // ---- escribe (PN-H) ------------------------------------------------------
    // PN-H1: cada elemento de la lista de impresión genera un PRINT
    @Override
    public void exitElemImp(PatitoParser.ElemImpContext ctx) {
        if (ctx.LETRERO() != null) {
            quads.enqueue("PRINT", ctx.LETRERO().getText(), "_", "_");
        } else { // expresion
            if (operandStack.isEmpty()) return;
            String val = operandStack.pop();
            typeStack.pop();
            quads.enqueue("PRINT", val, "_", "_");
        }
    }

    // ---- Condicional si / sino (PN-F) ----------------------------------------
    // PN-F2: al entrar al sinoOpcional, si tiene SINO, generamos GOTO y patch del GOTOF
    @Override
    public void enterSinoOpcional(PatitoParser.SinoOpcionalContext ctx) {
        if (ctx.SINO() == null) return;

        // Emitir GOTO que saltará al final del si/sino (destino se rellena en exitCondicion)
        int gotoIdx = quads.enqueue("GOTO", "_", "_", "?");
        // Patch del GOTOF previo: debe saltar al primer cuádruplo del else,
        // que es el siguiente al GOTO recién emitido.
        int pendingGotoF = jumpStack.pop();
        quads.patchResult(pendingGotoF, String.valueOf(quads.size()));
        jumpStack.push(gotoIdx);
    }

    // PN-F3: al cerrar la condicion, patch del último salto pendiente (GOTOF o GOTO según haya sino)
    @Override
    public void exitCondicion(PatitoParser.CondicionContext ctx) {
        if (jumpStack.isEmpty()) return;
        int pending = jumpStack.pop();
        quads.patchResult(pending, String.valueOf(quads.size()));
    }

    // ---- Ciclo mientras (PN-I) -----------------------------------------------
    // PN-I1: al entrar al ciclo, recordar el inicio de la condición
    @Override
    public void enterCiclo(PatitoParser.CicloContext ctx) {
        jumpStack.push(quads.size()); // donde inicia la evaluación de la condición
    }

    // (el GOTOF lo emite exitExpresion al detectar parent=CicloContext)

    // PN-I2: al cerrar el ciclo, emitir GOTO al inicio y patch del GOTOF
    @Override
    public void exitCiclo(PatitoParser.CicloContext ctx) {
        if (jumpStack.size() < 2) return; // hubo error previo
        int pendingGotoF = jumpStack.pop();
        int condStart    = jumpStack.pop();
        quads.enqueue("GOTO", "_", "_", String.valueOf(condStart));
        quads.patchResult(pendingGotoF, String.valueOf(quads.size()));
    }

    // ============================================================
    // SECCIÓN C — Helpers
    // ============================================================

    // Resuelve la operación binaria con el operador en la cima de la pila.
    // Saca dos operandos + dos tipos + un operador y emite un cuádruplo,
    // dejando el temporal resultado en la pila.
    private void generateBinaryQuadruple() {
        String op = operatorStack.pop();
        String right = operandStack.pop();
        String left  = operandStack.pop();
        SemanticCube.Type rightT = typeStack.pop();
        SemanticCube.Type leftT  = typeStack.pop();

        SemanticCube.Operator opEnum = mapOperator(op);
        SemanticCube.Type resultType = SemanticCube.getResultType(leftT, opEnum, rightT);
        if (resultType == SemanticCube.Type.ERROR) {
            errors.add("SEMÁNTICO: Tipos incompatibles para operador '" + op
                + "' entre " + leftT + " y " + rightT);
        }

        String temp = newTemp();
        quads.enqueue(op, left, right, temp);
        operandStack.push(temp);
        typeStack.push(resultType);
    }

    // Emite el GOTOF asociado a la condición de un si/mientras.
    private void generateConditionalGoToF(int line) {
        if (operandStack.isEmpty()) return;
        String cond = operandStack.pop();
        SemanticCube.Type t = typeStack.pop();
        if (t != SemanticCube.Type.ENTERO && t != SemanticCube.Type.ERROR) {
            errors.add("SEMÁNTICO [línea " + line
                + "]: La condición debe ser de tipo entero, se obtuvo " + t);
        }
        int idx = quads.enqueue("GOTOF", cond, "_", "?");
        jumpStack.push(idx);
    }

    private boolean topOperatorIsOneOf(String... ops) {
        if (operatorStack.isEmpty()) return false;
        String top = operatorStack.peek();
        for (String o : ops) if (o.equals(top)) return true;
        return false;
    }

    private String newTemp() {
        return "t" + (++tempCount);
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

    // Patito sólo tiene entero y flotante. Se permite asignar entero a flotante y al revés.
    private boolean isAssignable(SemanticCube.Type target, SemanticCube.Type expr) {
        if (target == SemanticCube.Type.ERROR || expr == SemanticCube.Type.ERROR) return true;
        return target == expr
            || (target == SemanticCube.Type.FLOTANTE && expr == SemanticCube.Type.ENTERO)
            || (target == SemanticCube.Type.ENTERO   && expr == SemanticCube.Type.FLOTANTE);
    }

    // Si estamos dentro de una función: tabla local; si no, tabla global.
    private VarTable resolveVarTable() {
        if (currentFunc != null && funcDirectory.contains(currentFunc)) {
            return funcDirectory.getFunc(currentFunc).localVars;
        }
        return globalVarTable;
    }

    // Busca primero en locales de la función actual, luego en globales.
    private SemanticCube.Type lookupVarType(String name) {
        if (currentFunc != null && funcDirectory.contains(currentFunc)) {
            VarInfo v = funcDirectory.getFunc(currentFunc).localVars.getVar(name);
            if (v != null) return v.type;
        }
        VarInfo v = globalVarTable.getVar(name);
        return v == null ? null : v.type;
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
    // SECCIÓN D — Acceso público
    // ============================================================

    public FuncDirectory   getFuncDirectory()  { return funcDirectory; }
    public VarTable        getGlobalVarTable() { return globalVarTable; }
    public List<String>    getErrors()         { return errors; }
    public boolean         hasErrors()         { return !errors.isEmpty(); }
    public QuadrupleQueue  getQuadruples()     { return quads; }
}
