# Análise do mod original — "Não bata em mulheres v2" (Izerli, 2011)

Fonte da ideia: <https://www.mixmods.com.br/2015/07/nao-bata-em-mulheres-v2/>

O código abaixo é o `.cs` original descompilado (Sanny), enviado junto com o
pedido de reescrita. Toda a numeração de linha se refere a esse listing.

```
{$CLEO .CS}
{$USE debug}

write_debug "ÍÅ ÁÅÉ ÆÅÍÙÈÍÓ 2, ÀÂÒÎÐ IZERLI/ÂÅÐÅÂÊÈÍ ÈÂÀÍ, 2011 ÃÎÄ"
script_name 'MYDAK'

:MYDAK_69
wait 2000

:MYDAK_74
wait 0
if
  is_player_playing $PLAYER_CHAR
goto_if_false @MYDAK_69
wait 0
if
  are_any_chars_near_char $PLAYER_ACTOR in_range 2.0
goto_if_false @MYDAK_528
1@, 2@, 3@ = get_char_coordinates $PLAYER_ACTOR
get_random_char_in_area_offset_no_save 1@ 2@ 3@ radius 2.0 2.0 2.0 handle_as 4@
if
  does_char_exist 4@
goto_if_false @MYDAK_74
if
  not is_char_male 4@
goto_if_false @MYDAK_528
if and
  not is_int_lvar_equal_to_int_lvar 4@ == 7@
  has_char_been_damaged_by_char 4@ damaged_by_actor $PLAYER_ACTOR
goto_if_false @MYDAK_528
set_lvar_int_to_lvar_int 7@ = 4@
create_group 8@ = create_group_type 5
set_group_leader 4@ in_group 8@
wait 10
1@, 2@, 3@ = get_char_coordinates 4@
if
  get_random_char_in_sphere_no_save_recursive 5@ = random_char_near_point 1@ 2@ 3@ in_radius 20.0 find_next 0 pass_deads 1
goto_if_false @MYDAK_386

:MYDAK_296
wait 0
if
  does_char_exist 5@
goto_if_false @MYDAK_356
if
  not is_int_lvar_equal_to_int_lvar 5@ == 4@
  is_char_male 5@
goto_if_false @MYDAK_356
set_group_member 5@ in_group 8@
task_kill_char_on_foot 5@ kill_actor $PLAYER_ACTOR

:MYDAK_356
  not get_random_char_in_sphere_no_save_recursive 5@ = random_char_near_point 1@ 2@ 3@ in_radius 20.0 find_next 1 pass_deads 1
goto_if_false @MYDAK_296

:MYDAK_386
wait 10
1@, 2@, 3@ = get_char_coordinates 4@
if
  get_random_char_in_sphere_no_save_recursive 6@ = random_char_near_point 1@ 2@ 3@ in_radius 20.0 find_next 0 pass_deads 1
goto_if_false @MYDAK_528

:MYDAK_438
wait 0
if
  does_char_exist 5@
goto_if_false @MYDAK_498
if
  not is_int_lvar_equal_to_int_lvar 6@ == 4@
  is_char_male 6@
goto_if_false @MYDAK_498
set_group_member 6@ in_group 8@
task_kill_char_on_foot 6@ kill_actor $PLAYER_ACTOR

:MYDAK_498
  not get_random_char_in_sphere_no_save_recursive 6@ = random_char_near_point 1@ 2@ 3@ in_radius 20.0 find_next 1 pass_deads 1
goto_if_false @MYDAK_438

:MYDAK_528
goto @MYDAK_74
```

---

## 1. Erros que impedem a compilação

São defeitos do listing (a descompilação se perdeu), mas valem registro porque
mostram que o material de partida não é recompilável como está.

| # | Onde | Problema |
|---|------|----------|
| E1 | `:MYDAK_356` / `:MYDAK_498` | Falta o `:` do rótulo — aparece como linha de condição solta dentro de um bloco `if`. |
| E2 | `if and` + `goto_if_false` | `goto_if_false` sem um `if`/`else_jump` válido no formato aceito pelos compiladores atuais. |
| E3 | `:MYDAK_356` e `:MYDAK_498` | Bloco `if` com **duas** condições sem `and`/`or`. |
| E4 | `{$USE debug}` + `write_debug` | Depende da extensão `debug` do gta3sc e de uma CLEO com suporte; sem isso o script nem compila. |
| E5 | `write_debug "ÍÅ ÁÅÉ..."` | Texto em **Windows-1251** (cirílico): "Не бей женщин 2, автор Izerli/Веревкин Иван, 2011 год". Em qualquer editor moderno vira lixo. |

## 2. Erros de lógica / bugs reais

### B1 — `:MYDAK_438` testa a variável errada
```
if
  does_char_exist 5@          <- deveria ser 6@
goto_if_false @MYDAK_498
```
O segundo bloco de recrutamento itera em `6@`, mas a guarda de existência
verifica `5@` (o contador do bloco anterior). É o tipo de erro de
copiar-e-colar que passa despercebido porque "quase sempre" funciona.

### B2 — Vazamento de grupos (`create_group` sem `remove_group`)
Cada agressão cria **um grupo novo** (`8@`) e nunca o libera. O GTA SA tem um
pool pequeno de grupos; depois de alguns minutos de jogo o pool estoura e o
script passa a falhar silenciosamente ou a corromper o comportamento de outros
mods/missões que também usam grupos.

### B3 — A vítima vira líder do grupo
```
set_group_leader 4@ in_group 8@
```
A mulher agredida passa a ser a líder de uma gangue cujo "alvo" é o jogador.
Na prática ela começa a seguir o CJ depois da agressão, em vez de fugir ou
reagir. Não há nenhuma reação da própria vítima no mod.

### B4 — As flags de dano nunca são consumidas
`has_char_been_damaged_by_char` continua verdadeiro até alguém chamar
`clear_char_last_damage_entity`. O script só se protege de re-disparar com
`7@` (a última vítima). Consequências:

* se o jogador bate **outra** vez na mesma mulher depois de algum tempo, o
  estado fica inconsistente;
* o dano fica "armado" indefinidamente: basta o jogador chegar perto de
  qualquer mulher já marcada para disparar uma nova onda.

### B5 — Sem limite de defensores (o "ponto fraco" que o próprio MixMods cita)
**Todos** os homens num raio de 20 m entram no grupo e recebem
`task_kill_char_on_foot`. Em áreas movimentadas (Vinewood, Santa Maria,
aeroporto) isso vira 20–40 peds espancando o jogador ao mesmo tempo — morte
garantida e impossível de reagir. Não existe cap, não existe escalonamento,
não existe variação de comportamento.

### B6 — Sem *cooldown* entre ondas
Nada impede uma nova onda a cada quadro. Combinado com B4 e B5, o resultado é
uma turba permanente.

### B7 — Sem checagem de linha de visão
Homens do outro lado da parede, dentro de prédios, em outro andar ou a 20 m
atrás de um muro também "veem" a agressão e atacam.

### B8 — Recrutamento em duas passadas, com `wait 0` por ped
Os dois blocos de recrutamento percorrem o pool de peds **duas vezes**
(`find_next 0` reinicia a iteração), e cada passo dá `wait 0`. Em área cheia
isso são dezenas de quadros só para recrutar — e a posição da vítima (`1@ 2@
3@`) fica congelada no valor lido antes do laço, ou seja, recruta-se em volta
de onde ela **estava**.

### B9 — Duplicação pura
`:MYDAK_296…` e `:MYDAK_438…` são o mesmo código com `5@`/`6@` trocados.
Nenhuma abstração, nenhuma sub-rotina.

### B10 — Nenhum filtro de contexto
Nada descarta: peds mortos/dying, peds em veículos, peds na água ou no ar,
**policiais** (que já têm a própria reação do jogo — e atacar o jogador aqui
só polui o nível de procurado), **peds de missão** (`PEDTYPE_MISSION1..8`, o
que pode quebrar missões em andamento), membros do grupo do jogador
(namoradas, colegas recrutados) e peds que já estão em combate.

### B11 — `are_any_chars_near_char $PLAYER_ACTOR in_range 2.0` é redundante
Só serve para pular o scan quando não há ninguém a 2 m — e o
`get_random_char_in_area_offset_no_save` logo abaixo já responde isso.

### B12 — `wait 2000` + `wait 0` inconsistentes
O laço principal gira a `wait 0` (todo quadro) fazendo um scan completo, o que
é desperdício; e depois de cada agressão o script cai em `@MYDAK_69` e dorme
2 s, o que é arbitrário.

### B13 — Zero feedback para o jogador
Nenhuma mensagem, som ou aviso. O jogador apanha de repente sem entender o
motivo — o que é justamente a graça do mod.

### B14 — Nada é configurável
`2.0`, `20.0`, `wait 10`, tipo de grupo `5`: tudo hardcoded e espalhado.

### B15 — Sem `0A93: end_custom_thread` e sem `0A95: enable_thread_saving`
O script nunca termina de forma controlada e não salva estado; ao carregar um
save, o mod volta ao estado inicial sem qualquer consistência.

### B16 — Sem proteção de estado do jogador
Não há checagem de missão (`$ONMISSION`), de cutscene, de wasted/busted. O mod
recruta turbas durante missões.

## 3. O que o original tem de bom (e foi mantido)

* **O gatilho certo**: `has_char_been_damaged_by_char mulher $PLAYER_ACTOR` é
  exatamente a forma correta de detectar "o jogador bateu nela" — barato,
  confiável e sem depender de animação/arma.
* **`not is_char_male`** para identificar a vítima: simples e funciona.
* **`4@ <> 7@`** para não reagir duas vezes à mesma mulher no mesmo instante.
* **`{$CLEO .CS}` + thread permanente com `wait`** em todos os laços: nunca
  trava o jogo.
* **`is_player_playing`** como guarda principal do laço.
* **Usar grupo** para coordenar a perseguição é uma ideia válida (só foi mal
  executada: líder errado + nunca removido).
* **Não apagar os peds recrutados** no final está correto: são peds do mundo,
  não criados pelo script.

## 4. Como a reescrita resolve cada ponto

| Problema | Solução em `src/MOBBNOBRAVEZA.sc` |
|---|---|
| B1 | Um único laço de recrutamento, sem duplicação (B9 também). |
| B2 | **Nenhum grupo é criado.** O defensor recebe uma única tarefa individual (`TASK_KILL_CHAR_ON_FOOT`). Sem `create_group`, não há vazamento possível. |
| B3 | **A vítima não é tocada.** Nenhuma task, nenhum grupo, nenhuma mudança de IA: o bug original (ela virar líder e seguir o CJ) é impossível porque o script nunca escreve nada nela. As únicas chamadas que a mencionam são `CLEAR_CHAR_LAST_DAMAGE_ENTITY` / `CLEAR_CHAR_LAST_WEAPON_DAMAGE`, que limpam o *registro* de dano que o jogo guarda sobre ela — não uma decisão dela (ver §5). |
| B4 | `CLEAR_CHAR_LAST_DAMAGE_ENTITY` + `CLEAR_CHAR_LAST_WEAPON_DAMAGE` consomem a flag no momento da reação. |
| B5 | `MAX_DEFENDERS = 5`: teto global de defensores simultâneos, com slot liberado quando expira. Quem não couber não é tocado — o jogo continua o controlando. |
| B6 | `WAVE_COOLDOWN = 8 s` (global) e `VICTIM_COOLDOWN = 25 s` (por vítima). |
| B7 | `IS_LINE_OF_SIGHT_CLEAR` entre a vítima e a testemunha, com o raio elevado a `EYE_HEIGHT = 0.7` para não bater no chão. |
| B8 | Uma passada só, com `RECRUIT_WINDOW = 2.5 s` e `RECRUIT_STEP_DELAY = 120 ms` entre recrutamentos; a posição da vítima é **relida a cada quadro**. |
| B10 | Filtros por pedtype (`PEDTYPE_COP`, `PEDTYPE_MISSION1..8`, `PEDTYPE_PLAYER1..PLAYER_UNUSED`), `IS_CHAR_SCRIPT_CONTROLLED` (CLEO+, peds criados/adotados por script), `IS_CHAR_ON_FOOT`, `IS_CHAR_IN_WATER`, `IS_CHAR_IN_AIR`, `IS_CHAR_DEAD`, `DOES_CHAR_EXIST`, vítima e jogador excluídos, e checagem contra os 5 slots já recrutados. |
| B11 | O `are_any_chars_near_char` sumiu: o `0AE1` já resolve. |
| B12 | O laço principal é `WHILE TRUE` + `WAIT SCAN_INTERVAL` (200 ms). Não há mais hotkey, então nada precisa ser lido a cada frame — o `WAIT 0` só aparece dentro da janela de recrutamento, que dura no máximo 2.5 s (§3.11 do `docs/COMPILER.md` para o `WHILE TRUE`). |
| B13 | **Deliberadamente não corrigido.** O requisito é não haver nenhum texto de *jogo* na tela: nenhuma mensagem, nenhuma help box. O feedback do mod continua sendo a própria turba, e o estado é exposto nas variáveis compartilhadas CLEO 3100/3101 para quem quiser construir um aviso por fora. O que existe agora é texto de **depuração** via `0662/0663`, que só aparece para quem tem ScrDebug ou o plugin DebugUtils do CLEO5 instalado e o bit `OPT_DEBUG_TEXT` ligado (§5.5). |
| B14 | Bloco `CONST_INT`/`CONST_FLOAT` único no topo + campo de bits `OPTIONS_DEFAULT` para ligar/desligar features. |
| B15 | Fim controlado com `TERMINATE_THIS_CUSTOM_SCRIPT` (gerado pelo gta3sc). `0A95` não é usado de propósito: o estado do mod é deliberadamente efêmero. |
| B16 | `IS_PLAYER_PLAYING`, `DOES_CHAR_EXIST(player)`, `IS_CHAR_IN_ANY_CAR(player)`, **`IS_ON_MISSION`, `IS_ON_CUTSCENE`, `IS_ON_SCRIPTED_CUTSCENE`** (CLEO+) e liberação total da turba quando qualquer uma falha. |
| E1–E5 | Reescrito do zero em GTA3script moderno (gta3sc), UTF-8, sem extensão `debug`. |

### O que o original fazia e a reescrita **não** faz mais

Removido por requisito explícito (§5), e não por descuido:

| Removido | Por quê |
|---|---|
| `TASK_SMART_FLEE_CHAR` na vítima | Não se mexe na IA nem nas decisões da vítima. |
| `PRINT_HELP_STRING` (`0ACA`) em três pontos | Nenhum texto na tela. |
| Sorteio `ATTACK_CHANCE` / `FLEE_CHANCE` | Substituído por um filtro determinístico baseado nos dados do jogo (§5.2). |
| `TASK_SMART_FLEE_CHAR`, `TASK_TURN_CHAR_TO_FACE_CHAR`, `TASK_SHAKE_FIST`, `TASK_LOOK_AT_CHAR` em testemunhas | Só `TASK_KILL_CHAR_ON_FOOT` pode ser aplicada; quem não atacaria não é tocado. |
| `TASK_SET_CHAR_DECISION_MAKER` (65539), `SET_SENSE_RANGE`, `SET_CHAR_ACCURACY`, `SET_CHAR_KEEP_TASK` | Reescreviam a personalidade e os sentidos de um ped do mundo. O jogo continua no controle. |
| `SCRIPT_NAME` | CLEO 4 nomeia o script pelo arquivo. |
| Restauração de `SET_CHAR_ACCURACY` na liberação | Nunca foi alterada. |

### Extras que não existiam no original

* **Sempre ativo, sem nenhuma tecla.** Requisito explícito desta rodada: o mod
  não registra atalho nenhum. A variável compartilhada 3100 é escrita com `1`
  uma vez no boot e nunca mais lida pelo script; 3101 guarda o campo de bits de
  opções, para que *outro* script possa ler ou escrever.
* **`WHILE TRUE` / `RETURN_TRUE` / `RETURN_FALSE` / `IF GOSUB`.** A
  verificação de covardia virou um predicado de verdade (`IsCoward`), que devolve
  a resposta pelo *compare flag* em vez de por uma variável — e o laço principal
  usa `BREAK`/`CONTINUE` em vez do idioma `label:` + `GOTO label` do script de
  2011 (`docs/COMPILER.md` §3.11).
* **Texto de depuração** com os opcodes originais da Rockstar (`0662`/`0663`),
  que existem no jogo de varejo como *no-op* (§5.8).
* **Filtro de covardes por dados do jogo** (`0EB1 GET_CHAR_STAT_ID` +
  `0EFA GET_CHAR_FEAR`): em vez de sortear o que um homem vai fazer, o mod
  pergunta ao jogo se aquele ped é do tipo que fugiria — e, se for, não faz
  absolutamente nada com ele (§5.2).
* **Bloqueio real por missão e cutscene** (`0E1D`, `0E25`, `0EB7`), que com CLEO
  puro era impossível (§5.3).
* **Respeito a peds de outros mods** (`0E0A IS_CHAR_SCRIPT_CONTROLLED`, que é
  verdadeiro quando um script criou/adotou o ped) e a quem já está em combate
  corpo a corpo (`0E47 IS_CHAR_FIGHTING` — veja a nota sobre a semântica exata
  em §5.4).
* **Liberação limpa e cirúrgica**: ao expirar o tempo (`DEFENDER_TIMEOUT`), ou
  quando o jogador morre, é preso, entra num carro, inicia uma missão/cutscene ou
  desliga o mod, a tarefa só é limpa de quem **ainda tem o jogador como alvo de
  kill** (`0E44 GET_CHAR_KILL_TARGET_CHAR`). Se o jogo deu outra coisa para o ped
  fazer nesse meio tempo, o script não limpa nada. Como os handles vêm de opcodes
  `*_NO_SAVE`, o script nunca foi dono de nenhuma referência — não há nada para
  vazar.
* **Slot expirado é liberado antes de ser reutilizado.** O caminho que reaproveita
  um slot cujo dono estourou o `DEFENDER_TIMEOUT` libera o ped antigo primeiro;
  sem isso o handle seria sobrescrito com uma `TASK_KILL_CHAR_ON_FOOT` ainda
  ativa e nada mais para cronometrá-la — um homem perseguindo o jogador para
  sempre, que é o comportamento do original.

---

## 5. Requisitos desta reescrita

Registrados aqui porque mudam o comportamento em relação ao que a auditoria das
seções 1–3 sugere como "melhoria óbvia".

### 5.1 O que o mod pode e não pode fazer

1. **Nenhum texto de jogo na tela.** Nenhuma mensagem, help box ou GXT.
   Texto de *depuração* é permitido e foi pedido — só existe para quem instalou
   ScrDebug ou o plugin DebugUtils do CLEO5 (§5.5).
2. **Não mexer na IA nem nas decisões da vítima.** Ela não foge, não vira, não
   olha, não recebe task alguma.
3. **Não mexer em pedestres que não vão agir contra o jogador.** Quem não ataca
   continua sob controle normal do jogo: sem task, sem alteração de status
   (precisão, alcance de sentidos, *keep task*) e sem troca de *decision maker*.
4. **O mod não funciona com pedestres masculinos "covardes"** — eles são
   filtrados *antes* de qualquer recrutamento, em vez de receberem um
   comportamento de fuga/ameaça.
5. **A única ação permitida é `TASK_KILL_CHAR_ON_FOOT`** contra o jogador.
6. CLEO+ pode (e é) usado para melhorar o funcionamento.

### 5.2 O filtro de covardes

Com CLEO puro não existe opcode que diga qual é a personalidade de um ped — foi
por isso que o original sorteava dados. O CLEO+ expõe os dois campos de
`data/pedstats.dat` que o próprio jogo usa para decidir isso:

* **`0EB1 GET_CHAR_STAT_ID`** → a linha do ped no arquivo. A última coluna é
  *"Default decision maker"*, cujo cabeçalho documenta `4` como *"coward peds"*
  (o decision maker `R_Weak`, isto é, os peds que o jogo manda correr). Nove
  linhas têm `4`: `SENSIBLE_GUY`, `GEEK_GUY`, `SENSIBLE_GIRL`, `GEEK_GIRL`,
  `STEWARD`, `SHOPPER`, `OLDSHOPPER`, `SKATER`, `COWARD`. As sete masculinas são
  rejeitadas.
* **`0EFA GET_CHAR_FEAR`** → a coluna *Fear* (0–100, 100 = "medo de tudo"), que é
  o número usado pelo jogo para decidir quão rápido o ped foge. Rejeita acima de
  `MAX_FEAR = 70`; pega `TOURIST` (fear 100), que não é marcado como covarde mas
  entra em pânico, e qualquer valor que um mod de peds tenha alterado.

O intervalo de linhas `14..25` é rejeitado por inteiro, o que exclui também
`STREET_GUY` (dm 2), `SUIT_GUY` (dm 2), `OLD_GUY` (dm 2) e `TOUGH_GUY` (dm 3).
Não são covardes para o jogo — é uma escolha desta reescrita, derivada do
requisito 3 (só mexer com quem vai de fato agir), e está isolada num único teste
com comentário no fonte: remover as quatro linhas do intervalo devolve esses
peds ao recrutamento sem tocar em mais nada.

### 5.3 O fim da limitação de missões

O gta3sc **proíbe variáveis globais em scripts customizados** (`.cs`), então
`$ONMISSION` continua inacessível pela linguagem
([issue #104](https://github.com/thelink2012/gta3sc/issues/104)). O problema é
contornado pelo CLEO+: `0E1D IS_ON_MISSION` lê a global que o `0180` seta, que é
exatamente o `$ONMISSION`. O bloqueio durante missões deixou de ser heurístico.
Como esses opcodes não existem no `cleo.xml` do gta3sc (que para em `0xB16`),
eles são declarados em [`config/cleoplus.xml`](../config/cleoplus.xml) e o build
passa `--add-config` — detalhes em `docs/COMPILER.md` §5.

### 5.4 O que cada opcode CLEO+ realmente responde

Conferido no código-fonte do CLEO+ (`JuniorDjjr/CLEOPlus`, branch `main`, commit
`d04732be7251`) e não apenas na documentação — os detalhes estão em
[`docs/COMPILER.md`](COMPILER.md) §7.

| Opcode | O que responde de fato | Consequência para o mod |
|---|---|---|
| `0E1D IS_ON_MISSION` | A global que o `0180` seta é diferente de zero | Bloqueio total durante missões — o `$ONMISSION` que `.cs` não alcança |
| `0E25` / `0EB7` | Cutscene ativa / cutscene de missão com bordas *widescreen* | Nada é recrutado nem mantido durante cutscenes |
| `0E0A IS_CHAR_SCRIPT_CONTROLLED` | `m_nCreatedBy == 2`: um script **criou ou adotou** o ped | Pega peds de missão e de outros mods CLEO. **Não substitui** o teste de pedtype para personagens de missão que não foram criados por script |
| `0E47 IS_CHAR_FIGHTING` | O ped tem `TASK_SIMPLE_FIGHT` (1016) na cadeia de tasks **deste frame** | Significa "está trocando socos agora", e não "está hostil". Um defensor perseguindo o jogador ou atirando nele não é "fighting"; quem já está numa luta corporal é — e é justamente esse que não devemos ter a IA sobrescrita |
| `0E44 GET_CHAR_KILL_TARGET_CHAR` | O ponteiro de alvo lido de dentro da `TASK_COMPLEX_KILL_PED_ON_FOOT` (1000) que o `05E2` criou; `-1` se não houver alvo vivo | É a resposta exata para "ele ainda está na **nossa** task?", o que permite liberar sem tocar em ped que o jogo já reencaminhou |
| `0EB1 GET_CHAR_STAT_ID` | `m_Index`, o primeiro campo de `CPedStats` (`CPed +0x59C`) | É o **índice** da linha em `pedstats.dat`, não um ponteiro — confirmado pelo layout da struct |
| `0EFA GET_CHAR_FEAR` | `m_ucFear`, um `unsigned char` em `CPedStats +0x24` | É a coluna *Fear* de verdade (0–100), não um campo de bits |
| `0EE4 LOCATE_CHAR_DISTANCE_TO_CHAR` | Distância 3D ao quadrado entre dois peds ≤ raio² | Substitui os seis argumentos do `LOCATE_CHAR_ANY_MEANS_CHAR_3D` |

Os dados de `0E47` e `0E44` vêm de um cache que o CLEO+ reconstrói **para todos
os peds do pool, todo frame**, zerando os flags e percorrendo as cinco tasks
primárias e as cinco secundárias com todas as subtasks. Não há valor obsoleto
para contornar.

### 5.5 Os três requisitos acrescentados depois

Pedidos após a primeira entrega, e que mudam o desenho — não só o texto.

**a) "Tire qualquer comando de tecla, quero o mod sempre ativo."**

O `F10` e o `0E3D IS_KEY_JUST_PRESSED` saíram por completo, junto com as
variáveis `ENABLED` e `LAST_SCAN`. Consequências que valem registro:

* O laço principal deixou de precisar de resolução de frame, então o `WAIT 0`
  virou `WAIT SCAN_INTERVAL` e o custo ocioso caiu de ~150 avaliações por
  segundo para 5 (§6.2).
* A variável compartilhada 3100 passou a ser **escrita** com `1` no boot, e
  nunca lida pelo script. Ela continua lá porque é o contrato público do mod:
  outro script que consultava 3100 para saber se o mod estava ligado continua
  recebendo `1`.
* O campo de bits de opções ganhou um terceiro bit, `OPT_DEBUG_TEXT`, e
  `OPTIONS_DEFAULT` foi de `3` para `7` — depuração ligada por padrão, porque
  quem não tem ScrDebug não a vê de qualquer forma.

**b) "`RETURN_TRUE` e `RETURN_FALSE` são GTA3script válido."**

Estavam certos, e a afirmação anterior desta documentação ("esses comandos não
existem") era **errada** e foi retirada. Eles são aliases de `0x485
IS_PC_VERSION` e `0x59A IS_AUSTRALIAN_GAME`, que no PC respondem sempre `true` e
sempre `false`; GTA III e Vice City os tinham com esses nomes e o SA os perdeu, e
o Junior_Djjr os restaurou declarando os aliases no XML de configuração
([fórum MixMods t179](https://forum.mixmods.com.br/f16-utilidades/t179-gta3script-while-true-return_true-e-return_false)).

O que mudou no código com eles disponíveis:

* `IsCoward` virou um **predicado** que responde pelo *compare flag*
  (`RETURN_TRUE` / `RETURN_FALSE`) e é chamado com `IF GOSUB`, em vez de escrever
  um `0`/`1` numa variável que o chamador relê. São 8 caminhos verdadeiros e um
  falso.
* O laço principal é `WHILE TRUE` / `ENDWHILE` com `BREAK` e `CONTINUE`
  (precisa de `-fbreak-continue`), substituindo o `MAIN_LOOP:` + `GOTO MAIN_LOOP`
  que o script de 2011 era obrigado a usar.

Detalhes de compilação em [`docs/COMPILER.md`](COMPILER.md) §3.11 — inclusive que
o gta3sc aceita vários `<Command>` com o mesmo ID (é o que permite acrescentar o
alias sem quebrar `IS_PC_VERSION`) e que o decompilador mostra o *primeiro* nome
registrado, então `RETURN_TRUE` volta como `IS_PC_VERSION` no IR.

**c) Textos de depuração na tela.**

Implementados com os opcodes originais da Rockstar, `0662 WRITE_DEBUG` e
`0663 WRITE_DEBUG_WITH_INT`, que existem no jogo de varejo como *no-op* — a SBL
marca os três com `is_nop: true` — e já vêm declarados no `config/gtasa/commands.xml`
do gta3sc, então **não precisaram entrar no `--add-config`**. É o mesmo mecanismo
que o mod de 2011 usava para o crédito na tela, via `{$USE debug}` do Sanny.

São 9 linhas, todas atrás do bit `OPT_DEBUG_TEXT`:

| Onde | Linha |
|---|---|
| boot | `MenReact: loaded, always active, no hotkey` |
| a cada 200 ms | `MenReact defenders: N` e `MenReact cooldown left s: N` |
| missão rodando | `MenReact: idle, a mission is running` |
| agressão detectada | `MenReact: victim detected, recruiting witnesses` |
| sem slot livre | `MenReact: mob is full, witness left alone` |
| recrutou | `MenReact recruited, defenders now: N` |
| defensor expirou | `MenReact: defender timed out, released` |
| liberação total | `MenReact released defenders: N` |

Três coisas que a implementação forçou e estão documentadas em
[`docs/COMPILER.md`](COMPILER.md) §8: a string do `_WITH_INT` é um **rótulo**
(`"MenReact defenders" 3` vira `MenReact defenders: 3`), não uma string de
formato; o gta3sc **caixa-alta** os literais; e **não dá para passar texto por
`GOSUB`**, então cada linha é escrita no próprio ponto de chamada atrás de um
portão (`DebugGate`) que responde em `DBG_COUNT`.

Por isso não há log por candidato rejeitado no recrutamento: cada motivo
precisaria de um rótulo próprio para ficar legível, e os dois contadores do
`DebugStatus` já respondem à única pergunta que esse log serviria — "os filtros
estão rejeitando todo mundo?".

---

## 6. Revisão final (passo 8 do plano)

### 6.1 Risco de travamento

O risco real está em cinco dos opcodes CLEO+ usados, que **desreferenciam o ped
sem checagem de nulo** (`GET_CHAR_STAT_ID`, `GET_CHAR_FEAR`,
`GET_CHAR_KILL_TARGET_CHAR`, `IS_CHAR_FIGHTING`, `LOCATE_CHAR_DISTANCE_TO_CHAR`
— este último com *dois* handles). `CPools::GetPed` devolve `nullptr` para um
handle inválido, então handle ruim ali é travamento, não `false`. Só
`IS_CHAR_SCRIPT_CONTROLLED` checa.

A regra do script é, por isso, estrutural: um handle só chega a um opcode CLEO+
**no mesmo frame** em que saiu do `0AE1` (ou do `GET_PLAYER_CHAR`) e depois de
`DOES_CHAR_EXIST` + `IS_CHAR_DEAD`, sem nenhum `WAIT` entre a checagem e o uso —
o pool não tem como reclaimar um ped no meio de um frame. O único lugar em que um
handle atravessa frames é `DEFENDER_HANDLE[5]`, e toda leitura dele passa por
`DOES_CHAR_EXIST` antes de qualquer desreferência. A vítima recebe a mesma
checagem a cada frame da janela de recrutamento, porque `0EE4` usa os dois
handles.

Nos demais eixos de estabilidade do SA:

* nenhuma referência é adquirida (variantes `*_NO_SAVE`), então não há vazamento
  de referência nem `MARK_CHAR_AS_NO_LONGER_NEEDED` esquecido;
* nenhum `CGroup` é criado — o vazamento de grupos do original (B2) não tem como
  acontecer;
* nenhuma global é escrita, nada é salvo em disco;
* todo laço tem `WAIT`;
* o uso de `BREAK` dentro de `REPEAT` depende de `-fbreak-continue`, que o
  `build.sh` passa.

### 6.2 Custo por frame

| Situação | Custo |
|---|---|
| Ocioso (quase todo o tempo) | **5 opcodes a cada 200 ms**: `WAIT`, `GET_GAME_TIMER`, o `DebugStatus` desligado (2) e o *fast path* de `ReleaseExpiredDefenders` (1 comparador) |
| Quando há defensor em campo | o `REPEAT` de 5 slots e, por slot vivo, uma subtração e uma comparação |
| A cada agressão | `GET_CHAR_COORDINATES` e o `0AE1` num raio de 3 m |
| Durante a janela (2,5 s por agressão) | `WAIT 0` com `0AE1` num raio de 25 m e a cadeia de filtros por candidato |

Duas decisões de custo deliberadas:

* **O laço principal é `WAIT SCAN_INTERVAL` (200 ms), não `WAIT 0`.** Enquanto
  existiu um hotkey *edge-triggered* o laço tinha que rodar a cada frame, porque
  `IS_KEY_JUST_PRESSED` só é verdadeiro no frame em que a tecla baixa. Sem
  hotkey não há nada que precise de resolução de frame: o `WAIT 0` sumiu do laço
  principal e ficou só dentro da janela de recrutamento, que dura no máximo
  2.5 s. Isso trocou ~150 avaliações por segundo por 5 — o mesmo mod, um quinto
  do custo ocioso.
* **Os filtros de recrutamento vão do mais barato ao mais caro.** Distância
  (`0EE4`, um opcode) e linha de visão descartam a maior parte de uma rua cheia
  antes que qualquer leitura de dados do ped (`0EB1`, `0EFA`) aconteça.

### 6.3 Defeitos encontrados nesta revisão e corrigidos

1. **Slot expirado sobrescrito sem liberar o ped.** O caminho que reaproveita um
   slot cujo dono estourou o `DEFENDER_TIMEOUT` substituía o handle com a
   `TASK_KILL_CHAR_ON_FOOT` ainda ativa e nada mais para cronometrá-la: um homem
   perseguindo o jogador para sempre — exatamente o comportamento do original.
   Agora o ocupante antigo é liberado antes, e — porque não sobra nenhuma
   variável local para guardar um handle — **toda leitura do ocupante antigo vai
   direto a `DEFENDER_HANDLE[ROLL]`** em vez de passar por `DEFENDER` (29@), que
   nesse momento segura a testemunha que está sendo recrutada (§6.3bis, item 1).
2. **Liberação cega.** `CLEAR_CHAR_TASKS_IMMEDIATELY` era chamado em qualquer
   defensor expirado. Agora só em quem ainda tem o jogador como alvo de kill
   (`0E44`); se o jogo deu outra task ao ped, o script não interfere — o
   requisito 3 vale também na saída.
3. **Janela de recrutamento gasta à toa.** Se a vítima entra num carro, o
   recrutamento é interrompido em vez de varrer uma rua onde não há mais ninguém
   para defender.
4. **Rotinas de liberação sem *fast path*.** Com o laço rodando a cada frame,
   `ReleaseAllDefenders` passaria a ser chamado todo frame durante missões,
   cutscenes e dentro do carro. Como os slots são preenchidos do índice 0 para
   cima e só são liberados em ordem, slot 0 vazio implica lista vazia — um
   comparador resolve.

### 6.3bis Defeitos encontrados **nesta** rodada (sempre-ativo + ScrDebug)

Três, todos introduzidos pela reescrita desta rodada e todos pegos antes de
entregar — dois pela leitura do IR emitido, um pela leitura do próprio script:

1. **Reuso de slot perdia a testemunha.** A primeira versão liberava o ocupante
   antigo com `DEFENDER = DEFENDER_HANDLE[ROLL]` e depois relia
   `DEFENDER = DEFENDER_HANDLE[ROLL]` para "unificar" o caminho do slot vazio com
   o do reaproveitado. Só que entre as duas linhas o slot já tinha sido zerado,
   então a releitura devolvia `SLOT_EMPTY` e o `IF DEFENDER = SLOT_EMPTY: GOTO
   RECRUIT_NEXT_WITNESS` pulava o recrutamento **nos dois caminhos** — ou seja,
   o mod nunca recrutava ninguém. Corrigido lendo o slot *inline* na liberação e
   mantendo a testemunha em `DEFENDER` do começo ao fim.
2. **`REPEAT` incrementa a própria variável — e a subrotina a sobrescrevia.**
   `ReleaseOneDefender` usava `CURSOR` (24@) para o alvo de kill, e `CURSOR` é o
   contador dos dois `REPEAT` que o chamam. O IR mostra o mecanismo:
   `ADD_VAL_TO_INT_LVAR 24@ 1` no fim do corpo. Resultado:
   `ReleaseAllDefenders` liberava **um** defensor e encerrava o laço, deixando
   até quatro homens com `TASK_KILL_CHAR_ON_FOOT` permanente — o bug B5 do
   original, reintroduzido por um detalhe de linguagem. Corrigido estacionando e
   restaurando o contador em volta da chamada (`docs/COMPILER.md` §3.12).
3. **Uma subrotina de debug que não imprimia nada.** `GOSUB Debug "texto"` não
   existe em GTA3script: argumento de `GOSUB` cai em `0@..`, que pertence ao
   bloco `CLEO_ARGS`, e não há variável local de string. A rotina "compilava" e
   não escrevia linha nenhuma. Substituída por um portão (`DebugGate`) que
   responde num inteiro, com o texto escrito no próprio ponto de chamada
   (`docs/COMPILER.md` §8.3).

O item 2 é o que justifica o `--verify` ser parte do fluxo e não uma checagem
eventual: o defeito era invisível no fonte e evidente no bytecode.

### 6.3ter Defeitos que o playtest revelou (e o que passou a impedir que se repitam)

O build entregue para teste em jogo carregava, imprimia sua linha de debug e
**não fazia nada**. O dump do SCRLog mostrou por quê: `NOW` (18@) valia 0 e
`PX/PY/PZ` (20@..22@) também — ou seja, o portão de cooldown da onda via
`0 < WAVE_COOLDOWN` todo tick e o `CONTINUE` impedia a varredura de chegar a
executar. Nenhum ped era encontrado porque nenhum ped era procurado.

Três defeitos, todos da mesma família — uma subrotina sobrescrevendo uma
variável local que o chamador ainda usa:

1. **`DebugStatus` zerava `NOW`.** A rotina calculava "quantos segundos faltam
   de cooldown" em `NOW` (18@) e saturava o valor em 0. Ela é chamada *antes* do
   portão da onda, que lê `NOW` como timestamp. Um texto de debug — inofensivo
   em qualquer outra posição — desligava o mod inteiro. Corrigido usando
   `DEFENDER` (29@) como rascunho: `DebugStatus` não escreve mais em 18@, o que
   o IR confirma.
2. **`IsCoward` nunca retornava.** `RETURN_TRUE` é apenas um alias de
   `0485 IS_PC_VERSION`: ele seta o flag de comparação e **não** retorna; o
   `RETURN` continua sendo obrigatório e não pode ficar dentro do mesmo bloco
   `IF` (§3.7). Sem ele, cada caminho de `IsCoward` caía direto na subrotina
   seguinte do arquivo — `RecruitDefenders`, que abria sua própria janela de
   recrutamento com seus próprios `WAIT` e só então devolvia o controle, via
   *seu* `RETURN`. Reescrito com um único rótulo `COWARD_YES`, um `RETURN_TRUE`
   e um `RETURN_FALSE`, cada um seguido do seu `RETURN`
   (`docs/COMPILER.md` §3.11).
3. **A flag `findNext` do `0AE1` era uma variável local.** Nos dois laços de
   varredura o quinto parâmetro do opcode era `CURSOR` (24@) — a mesma variável
   usada como id de pedstat, tipo de ped, índice de slot e contador de `REPEAT`
   no corpo do laço. Qualquer rejeição devolvia ao opcode um valor que ninguém
   escolheu. Substituída por dois pontos de chamada com literais `0` e `1`:
   constante não pode ser corrompida (`docs/COMPILER.md` §3.15).

4. **Texto de debug fora dos limites do ScrDebug.** Conferindo a documentação do
   próprio ScrDebug depois do playtest: o parâmetro de string do `0662` cabe em
   **40 caracteres** (três literais do script tinham 41, 42 e 47) e as mensagens
   entram numa **lista rolante de 12** na lateral da tela — não é uma linha que a
   chamada seguinte substitui. Ou seja, o status escrito a cada tick (200 ms)
   varria a lista em ~2,4 s e empurrava justamente os eventos ("vítima
   detectada", "recrutado") para fora da tela. Além disso, `ReleaseAllDefenders`
   escrevia sua linha **sem passar pelo portão** `OPT_DEBUG_TEXT`, quebrando a
   única promessa do bloco de debug. Corrigido: literais encurtados, status com
   throttle de 3 s derivado do timer do jogo (não há operador de módulo nem local
   livre para guardar estado — `docs/COMPILER.md` §8.4), heartbeat quando o mod
   está ocioso, e a linha de missão movida para dentro da rotina já throttled.

É a quarta vez que a mesma classe de defeito chega a um `.cs` compilado. Ler o
IR funcionou três vezes e falhou na quarta, então a verificação passou a ser
mecânica:

* **`tools/check-clobbers.py`** recalcula, a partir do bytecode emitido, o
  conjunto de locais que cada subrotina pode escrever (seguindo `GOSUB`
  aninhados) e compara com o comentário `// clobbers:` acima do rótulo; depois
  caminha pelo grafo de fluxo a partir de cada chamada procurando um local que o
  callee sobrescreve enquanto algum caminho ainda o lê. Roda dentro de
  `./build.sh --verify`.
* **`tools/check-debug-text.py`** confere os dois limites do ScrDebug nos
  literais de debug (40 caracteres) e se nenhuma linha de debug é alcançável com
  o `OPT_DEBUG_TEXT` desligado. Pegou o item 4 acima.
* **`tools/selftest-clobbers.sh`** reintroduz o defeito 1 duas vezes — uma com o
  comentário omitindo a escrita, outra com o comentário admitindo — e só passa
  se o verificador recusar as duas. Existe porque a primeira versão do
  verificador tratava `NOW = NOW - WAVE_TIME` como escrita pura e, com isso,
  declarava "limpo" exatamente o bug que o originou.

### 6.4 O que continua sendo limitação

* **`DEFENDER_TIMEOUT` é absoluto, não por distância.** Um defensor desiste após
  45 s mesmo que o jogador continue perto. É o preço de não reintroduzir a
  perseguição permanente do original (B5); novas agressões recrutam de novo.
* **`MAX_FEAR` está calibrado para a `pedstats.dat` vanilla.** Um mod de peds que
  reescreva a coluna *Fear* do jogo inteiro desloca o corte. Os dois testes são
  independentes: `MAX_FEAR = 100` desliga o de fear e deixa só a blacklist por
  pedstat, que é a classificação do próprio jogo.
* **O requisito 3 exclui mais peds do que o jogo classificaria como covardes.**
  `STREET_GUY`, `SUIT_GUY`, `OLD_GUY` e `TOUGH_GUY` têm decision maker 2 ou 3 e
  ainda assim são rejeitados, porque na prática não intervêm. É uma escolha
  documentada em §5.2, isolada num único teste, e removível sem tocar no resto.
* **Dependência dura do CLEO+.** Sem ele o script nem carrega. É deliberado:
  perguntar se um ped é covarde é impossível com CLEO puro, e o requisito 4 não
  tem como ser atendido de outra forma.
* **Sem atalho de teclado, logo sem desligar em jogo.** Foi o pedido explícito
  desta rodada ("tire qualquer comando de tecla, quero o mod sempre ativo"), e
  não é reversível sem recompilar. O que resta é a variável compartilhada 3101:
  outro script pode limpar `OPT_DEBUG_TEXT` para silenciar a depuração, mas o
  recrutamento em si não tem bit de desligar — remover o `.cs` da pasta `cleo` é
  o único jeito.
* **O texto de depuração sai em caixa alta.** O gta3sc normaliza literais de
  string para maiúsculas no bytecode, então `"MenReact defenders"` aparece como
  `MENREACT DEFENDERS`. Não há flag que preserve a caixa
  (`docs/COMPILER.md` §8.2).
