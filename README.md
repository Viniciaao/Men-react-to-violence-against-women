# Men react to violence against women — "Não bata em mulheres"

Um mod **CLEO** para *GTA San Andreas* escrito em **GTA3script** e compilado com
[`thelink2012/gta3sc`](https://github.com/thelink2012/gta3sc) no Linux.

Quando o jogador bate numa mulher, os homens por perto **que realmente fariam
algo a respeito** partem para cima do jogador (`TASK_KILL_CHAR_ON_FOOT`).
Mais nada acontece:

* a **vítima nunca é tocada** — nenhuma task, nenhuma fuga, nenhuma mudança de
  IA ou de decisão. Ela continua exatamente como o jogo a deixou;
* um pedestre que **não agiria contra o jogador nunca é tocado** — covardes,
  quem já está lutando, peds de missão e peds controlados por outro script não
  são lidos duas vezes, não recebem task, não têm status alterado e não têm o
  "cérebro" (decision maker) trocado. O jogo continua os controlando
  normalmente;
* **nenhum texto aparece na tela**. Sem mensagem, sem help box, sem GXT;
* a única task que o script dá a alguém é `TASK_KILL_CHAR_ON_FOOT` contra o
  jogador, e ela só é retirada (`CLEAR_CHAR_TASKS_IMMEDIATELY`) de quem **ainda
  tem exatamente essa task** — se o jogo já tiver dado outra coisa para o ped
  fazer, o script não interfere.

É uma reescrita completa do mod *"Não bata em mulheres v2"* (Izerli, 2011,
publicado no [MixMods](https://www.mixmods.com.br/2015/07/nao-bata-em-mulheres-v2/)).
A ideia é a mesma; a execução é nova. **[`docs/ANALISE.md`](docs/ANALISE.md)**
contém a auditoria linha a linha do script original (16 bugs, 5 deles de
compilação) e o que cada um virou aqui.

---

## Instalação

1. Tenha o **CLEO 4.1+** instalado no GTA San Andreas.
2. Instale o **CLEO+** — <https://github.com/JuniorDjjr/CLEOPlus>.
3. Copie `bin/MOBBNOBRAVEZA.cs` para a pasta `CLEO` do jogo.
4. Jogue. O mod está **sempre ativo** — não existe tecla de liga/desliga, nem
   mensagem na tela. A reação é o próprio feedback.

   Opcional, só para quem desenvolve mods: instale o
   [ScrDebug](https://www.mixmods.com.br/2017/06/sa-scrdebug/) (ou o plugin
   `DebugUtils` do CLEO5) e o mod passa a escrever o que está fazendo na tela.
   Veja [Depuração](#depuracão-scrdebug--cleo5-debugutils).

> ### CLEO+ é obrigatório, não opcional
>
> Sem o CLEO+ o script nem carrega: ele para no primeiro opcode desconhecido.
> O motivo é que perguntar *"esse pedestre é covarde?"* é impossível com CLEO
> puro — não existe opcode vanilla que leia a personalidade de um ped. O mod
> precisa disso para **não** mexer com quem fugiria em vez de agir.

Opcodes usados:

| Origem | Opcodes |
|---|---|
| CLEO (vanilla) | `0AE1 GET_RANDOM_CHAR_IN_SPHERE_NO_SAVE_RECURSIVE`, `0AB3 SET_CLEO_SHARED_VAR`, `0B10/0B11 BIT_AND/BIT_OR` |
| **CLEO+** (9) | `0E1D IS_ON_MISSION`, `0E25 IS_ON_CUTSCENE`, `0EB7 IS_ON_SCRIPTED_CUTSCENE`, `0E0A IS_CHAR_SCRIPT_CONTROLLED`, `0E47 IS_CHAR_FIGHTING`, `0EFA GET_CHAR_FEAR`, `0EB1 GET_CHAR_STAT_ID`, `0E44 GET_CHAR_KILL_TARGET_CHAR`, `0EE4 LOCATE_CHAR_DISTANCE_TO_CHAR` |
| Rockstar (depuração) | `0662 WRITE_DEBUG`, `0663 WRITE_DEBUG_WITH_INT` — *no-op* no jogo de varejo |
| Aliases GTA3script | `0x485 TRUE`/`RETURN_TRUE`, `0x59A RETURN_FALSE` — declarados em `config/cleoplus.xml` |

Para outros mods / ferramentas: o estado é publicado em variáveis compartilhadas
CLEO — **3100** = `1`, sempre (o mod não tem como ser desligado em jogo, mas a
variável continua sendo o contrato público), **3101** = campo de bits das opções
ativas. Um script de fora pode escrever em **3101** para, por exemplo, limpar o
bit de depuração.

## O filtro de covardes

É a parte que o CLEO+ tornou possível, e usa os dados do próprio jogo em vez de
sorteio:

1. **`GET_CHAR_STAT_ID`** devolve a linha do ped em `data/pedstats.dat`. A
   última coluna desse arquivo é *"Default decision maker"*, e o cabeçalho do
   próprio jogo documenta o valor **4** como *"coward peds"* — os peds que o
   decision maker `R_Weak` manda correr. Exatamente nove linhas têm esse valor:
   `SENSIBLE_GUY`, `GEEK_GUY`, `SENSIBLE_GIRL`, `GEEK_GIRL`, `STEWARD`,
   `SHOPPER`, `OLDSHOPPER`, `SKATER` e `COWARD`. Sete são masculinas e são
   rejeitadas (as femininas nem chegam lá: `IS_CHAR_MALE` já as descartou).
   No fonte elas aparecem pelos nomes oficiais do enum `PEDSTAT` que o próprio
   CLEO+ publica para o gta3sc (`PEDSTAT_STEWARD`, `PEDSTAT_COWARD`, …), então os
   números não são chutados. O intervalo `PEDSTAT_STREET_GUY..PEDSTAT_TOUGH_GIRL`
   (14..25) é rejeitado inteiro de propósito — veja `docs/ANALISE.md` §5.2 se
   quiser recrutar também `STREET_GUY`/`SUIT_GUY`/`OLD_GUY`/`TOUGH_GUY`.
2. **`GET_CHAR_FEAR`** devolve a coluna *Fear* do mesmo arquivo (0–100,
   100 = "medo de tudo"). É o número que o jogo usa para decidir quão rápido um
   ped foge. Pega as linhas que não são marcadas como covardes mas entram em
   pânico — `TOURIST` tem fear 100 — e qualquer valor que um mod de peds ou uma
   `pedstats.dat` editada tenha subido. `MAX_FEAR = 100` desliga esse segundo
   teste e deixa só a blacklist por pedstat.

Valores reais da `pedstats.dat` do SA, para referência:

```
PSYCHO 0 | COP 10 | FIREMAN 10 | TAXIDRIVER 16 | GANG1-9 20 | CRIMINAL 30
TOUGH_GUY 30 | SUIT_GUY 35 | SUIT_GIRL 30 | OLD_GUY 40 | PROSTITUTE 40
SPORTSFAN 40 | STEWARD 40 | STREET_GUY 45 | OLDSHOPPER 45 | BEACH_GUY 52
GEEK_GUY 56 | TRAMP_MALE 60 | SENSIBLE_GUY 65 | COWARD 65 | TOURIST 100
```

## Configuração

Tudo está no bloco `CONST_*` no topo de [`src/MOBBNOBRAVEZA.sc`](src/MOBBNOBRAVEZA.sc):
edite e recompile com `./build.sh`.

| Constante | Padrão | O que faz |
|---|---|---|
| `OPTIONS_DEFAULT` | `7` | Campo de bits das features opcionais (veja abaixo) |
| `SCAN_INTERVAL` | `200` ms | Período do `WHILE TRUE` do laço principal |
| `VICTIM_SCAN_RADIUS` | `3.0` m | Raio em que se procura uma mulher agredida |
| `DEFEND_RADIUS` | `25.0` m | Raio em que as testemunhas reagem (3D) |
| `MAX_DEFENDERS` | `5` | **Teto** de defensores simultâneos (o original não tinha) |
| `RECRUIT_WINDOW` | `2500` ms | Por quanto tempo se procura testemunhas |
| `RECRUIT_STEP_DELAY` | `120` ms | Pausa entre dois recrutamentos (anti-turba) |
| `DEFENDER_TIMEOUT` | `45000` ms | Quando o defensor desiste e volta a ser ped comum |
| `MAX_FEAR` | `70` | Fear máximo aceitável (`GET_CHAR_FEAR`); `100` desliga o teste |
| `PEDSTAT_*` | — | Linhas da `pedstats.dat` rejeitadas como covardes |
| `WAVE_COOLDOWN` | `8000` ms | Intervalo mínimo entre duas reações |
| `VICTIM_COOLDOWN` | `25000` ms | Antes que a *mesma* mulher dispare de novo |
| `EYE_HEIGHT` | `0.7` | Altura do raio de linha de visão |

Bits de `OPTIONS_DEFAULT` (some os valores e coloque o total):

| Bit | Feature |
|---|---|
| `1` | `IGNORE_WHEN_IN_CAR` — não age enquanto o jogador dirige |
| `2` | `MELEE_ONLY` — ignora tiros/explosões, só socos e atropelamento |
| `4` | `DEBUG_TEXT` — escreve as linhas de depuração (só visíveis com ScrDebug) |

> O gta3sc não tem *constant folding*: não dá para escrever `IF CONST = 1`
> (erro `could not match alternative`, porque não existe
> `IS_CONSTANT_EQUAL_TO_CONSTANT`). Por isso as opções viram um campo de bits
> testado em runtime com `0B10 BIT_AND` — um opcode por checagem, e todos os
> interruptores continuam num lugar só.

## Construindo

```bash
./build.sh              # -> bin/MOBBNOBRAVEZA.cs
./build.sh --verify     # + desmonta o resultado em build/MOBBNOBRAVEZA.ir2.txt
./build.sh --rebuild-tools
```

O `build.sh` procura o compilador em `$GTA3SC`, depois em
`tools/gta3sc/build/gta3sc`, depois no `PATH`; se não achar, ele clona e
compila o gta3sc sozinho.

O equivalente em linha de comando:

```bash
gta3sc src/MOBBNOBRAVEZA.sc --config=gtasa \
      --add-config=config/cleoplus.xml \
      --guesser --cs -fbreak-continue -fno-entity-tracking \
      -o bin/MOBBNOBRAVEZA.cs
```

Os dois arquivos de suporte são parte do projeto, não detalhes do ambiente:

* **[`config/cleoplus.xml`](config/cleoplus.xml)** — declara os 9 opcodes CLEO+
  para o compilador, mais o enum oficial `PEDSTAT` e os aliases `TRUE` /
  `RETURN_TRUE` / `RETURN_FALSE` (que são `0x485` e `0x59A` renomeados; sem eles
  o `WHILE TRUE` e o predicado `IsCoward` não compilam). O `config/gtasa/cleo.xml`
  que vem com o gta3sc para em `0xB16`, então ele não conhece nenhum `0Exx`.
  Passado com `--add-config` (caminho absoluto: o gta3sc resolve caminhos
  relativos a partir do diretório de configuração *dele*).

  O CLEO+ **já traz** um XML para gta3sc (`(for developers)/gta3script/cleo.xml`),
  e cada declaração daqui foi comparada com ele — mesmo ID, mesma ordem e mesmos
  atributos. Usar o arquivo oficial direto não dá: ele é um *superset* de 439
  comandos e o `--add-config` **anexa**, o que sobrescreveria dois valores do
  enum `BONE` e traria centenas de comandos que este mod não usa. Daí o extrato
  mínimo. Análise completa em `docs/COMPILER.md` §5.1.

  Vale o registro porque esta documentação já afirmou o contrário: os aliases
  `RETURN_TRUE`/`RETURN_FALSE` do XML oficial **não são um problema** — o gta3sc
  aceita vários `<Command>` com o mesmo ID, que viram alternadores, então
  `IS_PC_VERSION` continua funcionando ao lado de `RETURN_TRUE`. É exatamente o
  mecanismo que o Junior_Djjr descreve no
  [fórum MixMods](https://forum.mixmods.com.br/f16-utilidades/t179-gta3script-while-true-return_true-e-return_false),
  e é o que `config/cleoplus.xml` faz aqui de propósito (§3.11 do COMPILER.md).
* **`-fno-entity-tracking`** — o verificador de tipos de entidade do gta3sc não
  propaga o tipo através de elementos de array, e guardar handles de ped num
  array (`DEFENDER_HANDLE[5]`) é justamente o design do mod. Sem a flag, todo
  uso posterior de `DEFENDER` vira `expected variable of type CHAR but got NONE`.
  É uma checagem só de compilação: não muda um byte do `.cs` gerado.
  Detalhes em [`docs/COMPILER.md`](docs/COMPILER.md), §3.14.

### O compilador no Linux

```bash
git clone --depth 1 https://github.com/thelink2012/gta3sc.git tools/gta3sc
bash tools/build-gta3sc.sh          # tools/gta3sc/build/gta3sc
```

O upstream manda usar CMake; [`tools/build-gta3sc.sh`](tools/build-gta3sc.sh)
faz o mesmo com `g++` puro (C++17), porque o CMake não estava disponível no
ambiente onde isto foi construído. Todas as dependências já vêm vendored em
`deps/`, então não precisa de nenhum pacote de sistema. Os dois detalhes que
custam tempo estão tratados lá: gerar `build/git-sha1.cpp` a partir de
`git-sha1.cpp.in` (esquecer isso dá erro de **link**, não de compilação) e
copiar `config/` para ao lado do executável.

**[`docs/COMPILER.md`](docs/COMPILER.md)** documenta o build e, principalmente,
as 11 diferenças de linguagem entre o GTA3script do gta3sc e o Sanny-flavour que
os tutoriais de CLEO usam — incluindo o fato de que **variáveis globais são
proibidas em `.cs`**, o que obriga a trocar `$PLAYER_ACTOR` por
`GET_PLAYER_CHAR 0 ...`
([issue #104](https://github.com/thelink2012/gta3sc/issues/104) continua aberta).

## Layout do repositório

```
src/MOBBNOBRAVEZA.sc     o mod (GTA3script, fonte único)
bin/MOBBNOBRAVEZA.cs     o mod compilado - é isto que vai para a pasta CLEO
config/cleoplus.xml      definição dos opcodes CLEO+ para o gta3sc
build.sh                 compila (e opcionalmente verifica) o mod
docs/ANALISE.md          auditoria do mod original de 2011 + o que mudou
docs/COMPILER.md         como o gta3sc foi construído e as pegadinhas da linguagem
docs/original-mod-2011.txt  listagem do mod original, para referência
tools/build-gta3sc.sh    build do compilador sem CMake
tools/sbl.py             consulta a Sanny Builder Library (sa.json) por opcode
tools/gta3sc/            clone do compilador       (ignorado pelo git)
tools/sbl/               clone da Sanny Builder Library (ignorado pelo git)
build/                   *.o, *.ir2.txt             (ignorado pelo git)
```

## Como funciona por dentro

```
WHILE TRUE / WAIT SCAN_INTERVAL (200 ms) - sempre ativo, nenhuma tecla
  ├─ ReleaseExpiredDefenders        (fast path: 1 comparador se não há ninguém)
  ├─ DebugStatus                    (2 contadores; nada sem OPT_DEBUG_TEXT)
  ├─ IS_PLAYER_PLAYING / GET_PLAYER_CHAR
  ├─ IS_ON_MISSION / IS_ON_CUTSCENE / IS_ON_SCRIPTED_CUTSCENE  → libera todos
  ├─ IS_CHAR_IN_ANY_CAR (opcional)                             → libera todos
  ├─ rate limit (WAVE_COOLDOWN)
  └─ varre peds a 3 m do jogador com 0AE1
       └─ mulher? a pé? foi danificada pelo jogador? (flags consumidas na hora)
            └─ RecruitDefenders     (janela de 2,5 s, WAIT 0)
                 └─ por testemunha, do filtro mais barato ao mais caro:
                    existe / vivo / homem / a pé / não está na água ou no ar
                    → não é controlado por script (0E0A)
                    → pedtype não é policial, missão ou player
                    → está a DEFEND_RADIUS da vítima (0EE4)
                    → linha de visão livre até ela
                    → não está já lutando (0E47)
                    → IF GOSUB IsCoward (pedstat 0EB1 e fear 0EFA) → pula
                    → ainda não foi recrutado, e há slot livre
                    → TASK_KILL_CHAR_ON_FOOT (nada mais)
```

A ordem dos filtros não é estética: distância e linha de visão descartam a maior
parte de uma rua cheia por um opcode cada, e os testes de personalidade (que
leem dados do ped) só rodam em quem sobrou.

Os handles vêm sempre da variante `*_NO_SAVE` do `0AE1`, ou seja, o script
**nunca é dono de nenhuma referência** de ped: não há o que vazar e não há
`MARK_CHAR_AS_NO_LONGER_NEEDED` para esquecer. Nenhum `CGroup` é criado (o
original criava um por agressão e nunca o removia).

## Depuração (ScrDebug / CLEO5 DebugUtils)

O mod escreve o que está fazendo usando os opcodes de depuração **da própria
Rockstar**, `0662 WRITE_DEBUG` e `0663 WRITE_DEBUG_WITH_INT`. Eles existem no jogo
de varejo como *no-op* — a Sanny Builder Library marca os três da família com
`is_nop: true` — então **quem não instalou nada não vê diferença nenhuma**: nem
texto, nem log, nem custo. É o mesmo mecanismo que o mod de 2011 usava para o
crédito na tela.

Para ver as linhas, instale um dos dois:

| Ferramenta | O que fazer |
|---|---|
| [ScrDebug](https://www.mixmods.com.br/2017/06/sa-scrdebug/) (Deji) | Só instalar. Reimplementa o sistema de depuração pré-lançamento e também religa os checadores de tecla `0735`/`0736` que a Rockstar usava para esconder cheats no `main.scm` |
| CLEO5 + plugin `DebugUtils` | Ligar `DebugUtils.General.LegacyDebugOpcodes = 1` no `.ini` |

As linhas (o gta3sc **caixa-alta** literais de string, então é assim que elas
aparecem):

```
MENREACT: LOADED, ALWAYS ACTIVE, NO HOTKEY     <- uma vez, no boot
MENREACT DEFENDERS: 2                          <- a cada 200 ms
MENREACT COOLDOWN LEFT S: 5                    <- a cada 200 ms
MENREACT: IDLE, A MISSION IS RUNNING
MENREACT: VICTIM DETECTED, RECRUITING WITNESSES
MENREACT: MOB IS FULL, WITNESS LEFT ALONE
MENREACT RECRUITED, DEFENDERS NOW: 3
MENREACT: DEFENDER TIMED OUT, RELEASED
MENREACT RELEASED DEFENDERS: 3
```

Duas coisas que a implementação impõe e que valem saber antes de editar:

* **A string do `WRITE_DEBUG_WITH_INT` é um rótulo, não um formato.** O CLEO5 faz
  `ss << text << ": " << value`, então `"MenReact defenders" 3` sai
  `MENREACT DEFENDERS: 3`. Escrever `%d` imprimiria o `%d`.
* **Não dá para passar texto por `GOSUB`.** Argumento de `GOSUB` cai em `0@..`,
  que pertence ao bloco `CLEO_ARGS`, e não existe variável local de string. Por
  isso cada linha é escrita no próprio ponto de chamada, atrás de um portão
  (`DebugGate`) que responde em `DBG_COUNT`:

```
GOSUB DebugGate
IF DBG_COUNT = 1
    WRITE_DEBUG "MenReact: victim detected, recruiting witnesses"
ENDIF
```

Desligue tudo com `OPTIONS_DEFAULT = 3` (tira o bit `4`), ou em tempo de jogo
escrevendo em **3101** a partir de outro script.

Detalhes completos em [`docs/COMPILER.md`](docs/COMPILER.md) §8.

## Missões

`0E1D IS_ON_MISSION` (CLEO+) lê a global que o `0180` seta — exatamente o
`$ONMISSION` que um script `.cs` compilado pelo gta3sc **não consegue** acessar,
porque globais são ilegais em custom scripts. Com ele o mod fica totalmente
bloqueado durante missões, e não por heurística. `IS_CHAR_SCRIPT_CONTROLLED`
(`0E0A`) cobre o resto: peds criados por outros mods CLEO ou por qualquer script
não são tocados.

## Créditos

* Ideia original: **Izerli / Веревкин Иван (2011)** — "Não bata em mulheres v2",
  publicado no MixMods. Este repositório é uma reimplementação independente,
  feita a partir da análise do script descompilado.
* Compilador: **Denilson "thelink2012" Amorim** — [gta3sc](https://github.com/thelink2012/gta3sc) (MIT).
* Opcodes estendidos: **JuniorDjjr** — [CLEO+](https://github.com/JuniorDjjr/CLEOPlus),
  de onde também vem o arquivo gta3sc oficial usado como referência
  (commit `d04732be7251`).
* Referência de opcodes: **Sanny Builder Library** —
  [sannybuilder/library](https://github.com/sannybuilder/library).
