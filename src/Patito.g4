grammar Patito;

// lexer

// palabras reservadas
PROGRAMA: 'programa';
VARS: 'vars';
INICIO: 'inicio';
FIN: 'fin';
ENTERO: 'entero';
FLOTANTE: 'flotante';
NULA: 'nula';
SI: 'si';
SINO: 'sino';
MIENTRAS: 'mientras';
HAZ: 'haz';
ESCRIBE: 'escribe';

// identificadores y constantes
CTE_FLOAT: [0-9]+ '.' [0-9]+; //CTE_FLOAT antes que CTE_INT para que ANTLR no consuma solo los dígitos
CTE_INT: [0-9]+;
LETRERO   : '"' .*? '"' ;

// operadores aritmeticos
MAS: '+';
MENOS: '-';
POR: '*';
ENTRE: '/';

// operadores relacionales
DIFERENTE: '!=';
IGUAL: '==';
MENORQUE: '<';
MAYORQUE: '>';

// asignacion
ASIGNA: '='; //este va separado para que no lo confunda con el de igual '=='

// Delimitadores
PARENTESISIZQ: '(';
PARENTESISDER: ')';
LLAVEIZQ: '{';
LLAVEDER: '}';
CORCHIZQ: '[';
CORCHDER: ']';
COMA: ',';
PUNTOYCOMA: ';';
DOSPUNTOS: ':';

// ID va al final para no capturar palabras reservadas)
ID: [a-zA-Z][a-zA-Z0-9]*;

WS: [ \t\r\n]+ -> skip;





programa: 
    PROGRAMA ID PUNTOYCOMA varsOpcional funcsOpcional INICIO cuerpo FIN EOF
;

varsOpcional: 
    vars
    |
;

funcsOpcional:
    funcs funcsOpcional
    |
;

vars:
    VARS listDecl
;

listDecl:
    listId DOSPUNTOS tipo PUNTOYCOMA listDecl
    | listId DOSPUNTOS tipo PUNTOYCOMA
;

listId:
    ID listIdComa
;

listIdComa:
    COMA ID listIdComa
    |
;

tipo:
    ENTERO
    | FLOTANTE
;

funcs:
    tipoOpcional ID PARENTESISIZQ paramsOpcional PARENTESISDER varsOpcional LLAVEIZQ cuerpo LLAVEDER PUNTOYCOMA
;

tipoOpcional: 
    NULA
    | tipo
;

paramsOpcional: 
    params
    |
;

params:
    ID DOSPUNTOS tipo paramsP
;

paramsP:
    COMA ID DOSPUNTOS tipo paramsP
    |
;

cuerpo:
    LLAVEIZQ listaEstatutos LLAVEDER
;

listaEstatutos:
    estatuto listaEstatutos
    |
;

estatuto:
    asigna
    | condicion
    | ciclo
    | llamada PUNTOYCOMA
    | imprime
    | CORCHIZQ listaEstatutos CORCHDER
;

asigna:
    ID ASIGNA expresion PUNTOYCOMA
;

condicion:
    SI PARENTESISIZQ expresion PARENTESISDER cuerpo sinoOpcional PUNTOYCOMA
;

sinoOpcional:
    SINO cuerpo
    |
;

ciclo:
    MIENTRAS PARENTESISIZQ expresion PARENTESISDER HAZ cuerpo PUNTOYCOMA
;

llamada:
    ID PARENTESISIZQ argsOpcional PARENTESISDER
;

argsOpcional:
    args
    |
;

args:
    expresion argsP
;

argsP:
    COMA expresion argsP
    |
;

imprime:
    ESCRIBE PARENTESISIZQ listaImp PARENTESISDER PUNTOYCOMA
;

listaImp:
    elemImp listaImpP
;

listaImpP:
    COMA elemImp listaImpP
    |
;

elemImp:
    expresion
    | LETRERO
;

expresion:
    exp relOpcional
;

relOpcional:
    opRel exp
    |
;

opRel:
    MENORQUE
    | MAYORQUE
    | DIFERENTE
    | IGUAL
;

exp:
    termino expP
;

expP:
    MAS termino expP
    | MENOS termino expP
    |
;

termino:
    factor terminoP
;

terminoP:
    POR factor terminoP
    | ENTRE factor terminoP
    | /* ε */
;

factor:
    PARENTESISIZQ expresion PARENTESISDER
    | signoOpcional factorBase
    | llamada
;

signoOpcional:
    MAS
    | MENOS
    |
;

factorBase:
    ID
    | cte
;

cte:
    CTE_INT
    | CTE_FLOAT
;