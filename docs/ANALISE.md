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
| B2 | **Nenhum grupo é criado.** Os defensores recebem tarefas individuais (`TASK_KILL_CHAR_ON_FOOT`, `TASK_SMART_FLEE_CHAR`, `TASK_SHAKE_FIST`). Sem `create_group`, não há vazamento possível. |
| B3 | A vítima **foge** do jogador (`TASK_SMART_FLEE_CHAR`) em vez de virar líder. |
| B4 | `CLEAR_CHAR_LAST_DAMAGE_ENTITY` + `CLEAR_CHAR_LAST_WEAPON_DAMAGE` consomem a flag no momento da reação. |
| B5 | `MAX_DEFENDERS = 5` (teto global de defensores simultâneos) e `ATTACK_CHANCE = 60%`: parte ataca, parte só grita, parte foge. |
| B6 | `WAVE_COOLDOWN = 8 s` (global) e `VICTIM_COOLDOWN = 25 s` (por vítima). |
| B7 | `IS_LINE_OF_SIGHT_CLEAR` entre a vítima e a testemunha, com o raio elevado a `EYE_HEIGHT = 0.7` para não bater no chão. |
| B8 | Uma passada só, com `RECRUIT_WINDOW = 2.5 s` e `RECRUIT_STEP_DELAY = 120 ms` entre recrutamentos; a posição da vítima é **relida a cada quadro**. |
| B10 | Filtros por pedtype (`PEDTYPE_COP`, `PEDTYPE_MISSION1..8`, `PEDTYPE_PLAYER1..PLAYER_UNUSED`), `IS_CHAR_ON_FOOT`, `IS_CHAR_IN_WATER`, `IS_CHAR_IN_AIR`, `IS_CHAR_DEAD`, `DOES_CHAR_EXIST`, vítima e jogador excluídos, e checagem contra os 5 slots já recrutados. |
| B11 | O `are_any_chars_near_char` sumiu: o `0AE1` já resolve. |
| B12 | `SCAN_INTERVAL = 200 ms` no laço principal (barato) e `WAIT 0` apenas dentro da janela de recrutamento (curta). |
| B13 | `PRINT_HELP_STRING` (CLEO `0ACA`) avisa na hora — sem depender de GXT. |
| B14 | Bloco `CONST_INT`/`CONST_FLOAT` único no topo + campo de bits `OPTIONS_DEFAULT` para ligar/desligar features. |
| B15 | Fim controlado com `TERMINATE_THIS_CUSTOM_SCRIPT` (gerado pelo gta3sc). `0A95` não é usado de propósito: o estado do mod é deliberadamente efêmero. |
| B16 | `IS_PLAYER_PLAYING`, `DOES_CHAR_EXIST(player)`, `IS_CHAR_IN_ANY_CAR(player)` e liberação total da turba quando qualquer uma falha. |
| E1–E5 | Reescrito do zero em GTA3script moderno (gta3sc), UTF-8, sem extensão `debug`. |

### Extras que não existiam no original

* **F10 liga/desliga em jogo** (com debounce de 800 ms).
* **Variáveis compartilhadas CLEO** (`0AB3`) nos slots 3100/3102, para outros
  mods lerem "mod ativo?" e "quantas ondas já aconteceram".
* **Decisão por testemunha**: 60% atacam, 15% fogem, 25% só ameaçam
  (`TASK_TURN_CHAR_TO_FACE_CHAR` + `TASK_SHAKE_FIST` + `TASK_LOOK_AT_CHAR`).
* **`TASK_SET_CHAR_DECISION_MAKER` com o template "random tough"** (65539) e
  `SET_SENSE_RANGE`, para o defensor não desistir no primeiro soco.
* **Liberação limpa**: ao expirar o tempo (`DEFENDER_TIMEOUT`), ou quando o
  jogador morre, é preso, entra num carro ou desliga o mod, cada defensor tem
  as tarefas limpas (`CLEAR_CHAR_TASKS_IMMEDIATELY`) e volta a ser um ped
  comum. Como os handles vêm de opcodes `*_NO_SAVE`, o script nunca foi dono de
  nenhuma referência — não há nada para vazar.

### Limitação conhecida (documentada de propósito)

O gta3sc **proíbe variáveis globais em scripts customizados** (`.cs`), então
`$ONMISSION` não é acessível (ver `docs/COMPILER.md`). A proteção contra
missões é feita por heurística: pedtypes de missão são ignorados, e o mod só
age com o jogador vivo, no controle e a pé. Para um bloqueio total por missão
seria preciso CLEO+ (`0E1D: is_on_mission`) — que o gta3sc ainda não conhece.
