# AI Static Analyzer

A hybrid static analyzer for Java: deterministic AST rules and an LLM-assisted
semantic pass, run over the same file and compared side by side — plus a
reproducibility experiment that measures how often each one agrees with itself.

Built for the Compilers course at Universidad EIA — Topic 15: *Artificial
Intelligence applied to compilers and language development*.

---

## Why hybrid

Classic static analysis is deterministic, fast and exact, but it only sees
**syntax**. It can prove that a variable is never read; it cannot notice that a
method called `calculateAverage` never divides.

A language model sees **intent**, but it is probabilistic. It does not return
an answer — it samples one.

This project runs both engines over the same file, shows where they overlap and
where each one is blind, and then measures something most tooling never checks:
whether either engine returns the same answer twice.

---

## Pipeline

```
File.java
   |
   v
[ JavaParser ] ------> AST
                        |
        +---------------+---------------+
        |                               |
        v                               v
  PASS 1: Classic                 PASS 2: AI
  Visitor rules over              Source sent to the
  the AST                         Claude API
        |                               |
        +---------------+---------------+
                        |
                        v
             PASS 3: Comparative report
          Classic only | AI only | Both agree
```

---

## Pass 1 — Classic rules

Each rule is a Visitor (Gang of Four Visitor Pattern) walking the AST produced
by JavaParser. Complexity is O(n) over the number of AST nodes per rule, so
O(r·n) for r rules — linear in the size of the source file.

| Rule | Detects |
|------|---------|
| `UnusedVariableRule` | Locals declared but never referenced |
| `UnreachableCodeRule` | Statements after `return` / `throw` / `break` / `continue` |
| `StringEqualityRule` | Strings compared with `==` instead of `.equals()` |
| `EmptyCatchRule` | Exceptions caught and silently discarded |

`ClassicAnalyzer` first collects every identifier declared as a `String` — a
poor man's symbol table — because `StringEqualityRule` needs type information
to distinguish a reference comparison from a legitimate one.

---

## Pass 2 — AI review

The source file, with line numbers prepended, is sent to the Anthropic Messages
API. The prompt explicitly excludes everything Pass 1 already covers and asks
for logic bugs, off-by-one errors, misleading identifiers and intent
mismatches. The model must reply with a JSON array, parsed into the same
`Finding` model the classic pass produces.

The raw reply is cached in `.cache/`, so a demo can be replayed with
`--offline` and produce an identical report every time. Given the findings
below, this is not a convenience — it is the only way to make a live
demonstration reproducible.

---

## Pass 3 — Comparative report

Findings from both passes are joined **by line number**.

That is a simplification, and we left it in deliberately. Two analyzers rarely
describe the same problem the same way, and they do not always point at the
same line either: when the classic rule reports the line where a `catch` clause
starts and the model reports the line where its empty body sits, one defect
shows up as two disagreeing findings. Reconciling output across a deterministic
engine and a probabilistic one is an open problem, not a detail — hiding it
behind fuzzy matching would have hidden the most interesting result.

---

## The reproducibility experiment

```bash
java -jar target/analyzer.jar samples/Buggy.java --repeat 5
```

The same file is analyzed N times by **both** passes. The classic pass is the
control group: it is deterministic by construction, so it must return an
identical report every run. Any instability in the AI pass is therefore a
property of the model, not of the harness. The cache is bypassed here — replaying
a stored answer would guarantee a perfect score and prove nothing.

Reproducibility is reported twice, because one number cannot separate two very
different failures.

**Strict** groups findings by exact line: *did the analyzer report the same
thing, in the same place, every time?* It is unforgiving — a defect the model
located at line 75 on one run and line 76 on the next becomes two
half-present findings, and neither counts as stable, even though the defect
was found on every run.

**Merged** first fuses findings on adjacent lines into one defect, then asks
the same question: *did the analyzer find the same problems every time,
ignoring where exactly it put them?*

Neither is the truth alone. Strict punishes a defect twice for a location the
model could not pin down; merged can fuse two genuinely different defects that
happen to sit next to each other. The gap between them is the interesting
quantity — it isolates how much disagreement comes from the model failing to
*see* a bug, versus failing to say *where* it is.

### Results — 7 experiments, 35 runs per pass

| Experiment | Classic (strict / merged) | AI strict | AI merged |
|---|---|---|---|
| 1 | 100% / 100% | 71% | 83% |
| 2 | 100% / 100% | 25% | 50% |
| 3 | 100% / 100% | 25% | 50% |
| 4 | 100% / 100% | 29% | 50% |
| 5 | 100% / 100% | 43% | 50% |
| 6 | 100% / 100% | 25% | 50% |
| 7 | 100% / 100% | 25% | 50% |

The classic pass scores identically under both metrics: none of its findings
ever landed on adjacent lines, so there was nothing to fuse. Six of the seven
AI experiments land on exactly 50% merged — half the defects reported every
time, half coming and going.

Experiments 1 and 2 passed `--temperature 1.0` explicitly; the rest omitted the
parameter. Since 1.0 is the model default, all seven ran under identical
conditions — which is why we could not treat them as two configurations.

| | Classic | AI |
|---|---|---|
| Findings per run (min–max) | 4 – 4 | 3 – 6 |
| Identical report every run | yes, 35/35 | never |
| Reproducibility, strict | 100% | 25% – 71% |
| Reproducibility, merged | 100% | 50% – 83% |

---

## What we found

**1. The AI pass never reproduced itself.** Across 35 runs on an unchanged
file with an unchanged prompt, it returned between 3 and 6 findings. The
classic pass returned the same 4 findings, with the same wording, 35 times out
of 35.

**2. Even the measurement of instability is unstable.** Two experiments run
back to back under identical conditions scored 71% and 25%. There is no single
number we can honestly report as "the model's reliability."

**3. Determinism is no longer a setting.** Our plan was to pin `temperature` to
0 and show the analyzer stabilizing. The API refused with HTTP 400: Anthropic
has deprecated `temperature`, `top_p` and `top_k` on recent models, and any
non-default value is rejected outright. The recommended approach is now
prompt-level control instead of sampling-level control. The knob developers
used to reach for is gone.

**4. The model is stable exactly where it is irreplaceable.** The off-by-one
error at line 36 and the misleading method name at line 48 — the two defects
no syntactic rule can express — were reported in **35 runs out of 35**. Every
finding that wobbled was one the classic pass already covers. The AI is
reliable on deep semantic analysis and unreliable on shallow pattern matching,
which is a strong argument for the hybrid design rather than against it.

**5. It cannot agree with itself on where a bug lives.** The swallowed
exception was reported at line 75 in some runs and line 76 in others; the
inverted discount at line 84 or 85. This is a distinct failure from missing a
bug, and it is what the two metrics separate: across the seven experiments,
merging adjacent lines lifts reproducibility from 25–71% to 50–83%. Roughly
half the apparent instability is not the model missing defects — it is the
model finding them and disagreeing with itself about their address.

**6. It does not always follow instructions.** The prompt explicitly forbids
reporting unused variables, unreachable code, empty catch blocks and `==`
string comparison. The model reported them anyway in most runs. In one run out
of 35 it complied perfectly — and that run produced a comparative report with
**zero overlap** between the two passes: four findings each, completely
disjoint.

---

## Conclusion

A compiler pass that returns a different answer on the same input is not a
compiler pass. It is an advisor.

That is not a reason to leave AI out of the toolchain — the model found three
real defects our rules are structurally incapable of expressing, and it found
them every single time. It is a reason to keep the deterministic passes
underneath it. Reproducibility is not a feature you add to a language model; it
is a property the classic passes already have, and the reason they should stay.

---

## Requirements

- Java 17 or newer
- Maven 3.8 or newer
- An Anthropic API key (only for the online AI pass)

## Setup

```bash
export ANTHROPIC_API_KEY="your-key-here"
mvn clean package
```

The key is read from the environment and never committed. Optionally set
`ANTHROPIC_MODEL` to override the default model.

## Usage

```bash
# Classic pass + AI pass + comparative report
java -jar target/analyzer.jar samples/Buggy.java

# Replay the cached AI response — no network, identical output every time
java -jar target/analyzer.jar samples/Buggy.java --offline

# Deterministic pass only
java -jar target/analyzer.jar samples/Buggy.java --classic-only

# Reproducibility experiment
java -jar target/analyzer.jar samples/Buggy.java --repeat 5

# Any of the above, plus a rendered HTML report opened in the browser
java -jar target/analyzer.jar samples/Buggy.java --repeat 5 --html
```

| Flag | Effect |
|------|--------|
| `--offline` | Replay the cached AI response instead of calling the API |
| `--classic-only` | Skip the AI pass entirely |
| `--repeat N` | Run the reproducibility experiment N times |
| `--temperature X` | Sampling temperature; omitted from the request if not given |
| `--html` | Write a self-contained HTML report to `report/` and open it |

`--temperature` is kept for older models. Current models reject it.

## Sample file

`samples/Buggy.java` contains seven planted defects: four the classic rules
catch, three only the AI pass reports. It lives outside `src/` because it does
not compile — the unreachable statement after a `return` is a compile error in
Java, which is precisely what `UnreachableCodeRule` replicates. It is input
data, not project source.

## Project structure

```
src/main/java/com/eia/analyzer/
├── Main.java                       CLI entry point, orchestrates the passes
├── model/Finding.java              Shared result model
├── classic/
│   ├── ClassicAnalyzer.java        Runs the rules, builds the symbol set
│   └── rules/                      One Visitor per rule
├── ai/
│   ├── ClaudeClient.java           Anthropic Messages API client
│   └── AiAnalyzer.java             Prompt, JSON parsing, offline cache
└── report/
    ├── ComparativeReport.java      Side-by-side console report
    ├── VarianceReport.java         Reproducibility experiment
    └── HtmlReport.java             Self-contained HTML rendering
```

## License

Academic project. Free to read, fork and learn from.
