# Men react to violence against women — "Não bata em mulheres"

Um mod **CLEO** para *GTA San Andreas* escrito em **GTA3script** e compilado com
[`thelink2012/gta3sc`](https://github.com/thelink2012/gta3sc) no Linux.

Quando o jogador bate numa mulher, os homens por perto reagem:

* **~60 %** partem para cima do jogador (`TASK_KILL_CHAR_ON_FOOT`), com
  precisão e "cérebro" ajustados para não desistirem no primeiro soco;
* **~15 %** fogem;
* **~25 %** só encaram e ameaçam (`TASK_SHAKE_FIST`);
* a **própria vítima foge** do jogador em vez de ficar parada apanhando.

É uma reescrita completa do mod *"Não bata em mulheres v2"* (Izerli, 2011,
publicado no [MixMods](https://www.mixmods.com.br/2015/07/nao-bata-em-mulheres-v2/)).
A ideia é a mesma; a execução é nova. **[`docs/ANALISE.md`](docs/ANALISE.md)**
contém a auditoria linha a linha do script original (16 bugs, 5 deles de
compilação) e o que cada um virou aqui.

---

## Instalação

1. Tenha o **CLEO 4.1+** instalado no GTA San Andreas.
2. Copie `bin/MOBBNOBRAVEZA.cs` para a pasta `CLEO` do jogo.
3. Jogue. **F10** liga/desliga o mod em tempo real.

Só dois opcodes CLEO são usados (`0ACA PRINT_HELP_STRING` e
`0AE1 GET_RANDOM_CHAR_IN_SPHERE_NO_SAVE_RECURSIVE`), então qualquer CLEO 4
moderna serve — não precisa de CLEO+.

## Configuração

Tudo está no bloco `CONST_*` no topo de [`src/MOBBNOBRAVEZA.sc`](src/MOBBNOBRAVEZA.sc):
edite e recompile com `./build.sh`.

| Constante | Padrão | O que faz |
|---|---|---|
| `OPTIONS_DEFAULT` | `27` | Campo de bits das features opcionais (veja abaixo) |
| `ENABLED_ON_START` | `1` | Começa ligado |
| `SCAN_INTERVAL` | `200` ms | Intervalo do laço principal (barato) |
| `VICTIM_SCAN_RADIUS` | `3.0` m | Raio em que se procura uma mulher agredida |
| `DEFEND_RADIUS` | `25.0` m | Raio em que as testemunhas reagem |
| `DEFEND_RADIUS_Z` | `8.0` m | Tolerância vertical desse raio |
| `MAX_DEFENDERS` | `5` | **Teto** de defensores simultâneos (o original não tinha) |
| `RECRUIT_WINDOW` | `2500` ms | Por quanto tempo se procura testemunhas |
| `RECRUIT_STEP_DELAY` | `120` ms | Pausa entre dois recrutamentos (anti-turba) |
| `DEFENDER_TIMEOUT` | `45000` ms | Quando o defensor desiste e volta a ser ped comum |
| `ATTACK_CHANCE` | `60` % | Probabilidade de atacar de verdade |
| `FLEE_CHANCE` | `15` % | Probabilidade de fugir (o resto só ameaça) |
| `DEFENDER_ACCURACY` | `55` | Precisão de quem ataca |
| `DEFENDER_SENSE_RANGE` | `35.0` | Alcance de visão/audição do defensor |
| `WAVE_COOLDOWN` | `8000` ms | Intervalo mínimo entre duas reações |
| `VICTIM_COOLDOWN` | `25000` ms | Antes que a *mesma* mulher dispare de novo |
| `VICTIM_FLEE_RADIUS` / `VICTIM_FLEE_TIME` | `30.0` / `6000` | Fuga da vítima |
| `EYE_HEIGHT` | `0.7` | Altura do raio de linha de visão |

Bits de `OPTIONS_DEFAULT` (some os valores e coloque o total):

| Bit | Feature |
|---|---|
| `1` | `SHOW_HELP_TEXT` — mensagens na tela |
| `2` | `IGNORE_WHEN_IN_CAR` — não age enquanto o jogador dirige |
| `4` | `MELEE_ONLY` — ignora tiros/explosões, só socos e atropelamento |
| `8` | `VICTIM_FLEES` — a vítima foge |
| `16` | `USE_TOUGH_BRAIN` — defensores usam o decision maker *random tough* |
| `32` | *(interno)* — há defensores ativos; não configure |

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
gta3sc src/MOBBNOBRAVEZA.sc --config=gtasa --guesser --cs -fbreak-continue \
      -o bin/MOBBNOBRAVEZA.cs
```

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
as 10 diferenças de linguagem entre o GTA3script do gta3sc e o Sanny-flavour que
os tutoriais de CLEO usam — incluindo o fato de que **variáveis globais são
proibidas em `.cs`**, o que obriga a trocar `$PLAYER_ACTOR` por
`GET_PLAYER_CHAR 0 ...` e torna `$ONMISSION` inacessível
([issue #104](https://github.com/thelink2012/gta3sc/issues/104) continua aberta).

## Layout do repositório

```
src/MOBBNOBRAVEZA.sc     o mod (GTA3script, fonte único)
bin/MOBBNOBRAVEZA.cs     o mod compilado - é isto que vai para a pasta CLEO
build.sh                 compila (e opcionalmente verifica) o mod
docs/ANALISE.md          auditoria do mod original de 2011 + o que mudou
docs/COMPILER.md         como o gta3sc foi construído e as pegadinhas da linguagem
tools/build-gta3sc.sh    build do compilador sem CMake
tools/sbl.py             consulta a Sanny Builder Library (sa.json) por opcode
tools/gta3sc/            clone do compilador       (ignorado pelo git)
tools/sbl/               clone da Sanny Builder Library (ignorado pelo git)
build/                   *.o, *.ir2.txt             (ignorado pelo git)
```

## Como funciona por dentro

```
laço principal (200 ms)
  ├─ hotkey F10 (com debounce)
  ├─ IS_PLAYER_PLAYING / GET_PLAYER_CHAR / IS_CHAR_IN_ANY_CAR
  ├─ ReleaseExpiredDefenders            (só se houver defensor ativo)
  ├─ rate limit global (WAVE_COOLDOWN)
  └─ varre peds a 3 m do jogador com 0AE1
       └─ mulher? foi danificada pelo jogador? (flags consumidas na hora)
            ├─ MakeVictimReact          (ela foge)
            └─ RecruitDefenders         (janela de 2,5 s, WAIT 0)
                 └─ por testemunha: homem? a pé? não é policial/missão/player?
                    dentro do raio? linha de visão livre? já não recrutado?
                    tem slot livre?  → ReactDefender (dado: ataca/foge/ameaça)
```

Os handles vêm sempre da variante `*_NO_SAVE` do `0AE1`, ou seja, o script
**nunca é dono de nenhuma referência** de ped: não há o que vazar e não há
`MARK_CHAR_AS_NO_LONGER_NEEDED` para esquecer. Nenhum `CGroup` é criado (o
original criava um por agressão e nunca o removia).

## Limitação conhecida

Sem `$ONMISSION` (globais são ilegais em `.cs` no gta3sc) não dá para bloquear
o mod durante missões de forma absoluta. A proteção é por heurística: pedtypes
de missão (`PEDTYPE_MISSION1..8`) são ignorados, policiais são ignorados, e o
mod só age com o jogador vivo, no controle e a pé. Um bloqueio total exigiria
CLEO+ (`0E1D: is_on_mission`), que o gta3sc ainda não conhece.

## Créditos

* Ideia original: **Izerli / Веревкин Иван (2011)** — "Não bata em mulheres v2",
  publicado no MixMods. Este repositório é uma reimplementação independente,
  feita a partir da análise do script descompilado.
* Compilador: **Denilson "thelink2012" Amorim** — [gta3sc](https://github.com/thelink2012/gta3sc) (MIT).
* Referência de opcodes: **Sanny Builder Library** —
  [sannybuilder/library](https://github.com/sannybuilder/library).
