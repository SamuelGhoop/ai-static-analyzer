# AI Static Analyzer

A hybrid static analyzer for Java source code: deterministic AST rules and an
LLM-assisted semantic pass, running over the same file and compared side by side.

Built for the Compilers course at Universidad EIA — Topic 15: *Artificial
Intelligence applied to compilers and language development*.

## Why hybrid

Classic static analysis is deterministic, fast and exact, but it only sees
**syntax**. It can prove that a variable is never read; it cannot notice that a
method called `calculateAverage` never divides.

A language model sees **intent**, but it is non-deterministic and can
hallucinate defects that do not exist.

This project runs both over the same file and shows where they overlap, where
each one is blind, and why the interesting future of compiler tooling is the
combination rather than either half.

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

## Classic rules (Pass 1)

Each rule is a Visitor (Gang of Four Visitor Pattern) walking the AST.
Complexity is O(n) over the number of AST nodes per rule.

| Rule | Detects |
|------|---------|
| `UnusedVariableRule` | Locals declared but never referenced |
| `UnreachableCodeRule` | Statements after `return` / `throw` / `break` / `continue` |
| `StringEqualityRule` | Strings compared with `==` instead of `.equals()` |
| `EmptyCatchRule` | Exceptions caught and silently discarded |

## AI pass (Pass 2)

The source file is sent to the Anthropic Messages API with a prompt that
explicitly excludes everything the classic pass already covers, and asks for
logic bugs, off-by-one errors, misleading identifiers and intent mismatches.
The model must reply with a JSON array, which is parsed into the same `Finding`
model the classic pass produces.

The raw reply is cached in `.cache/` so the demo can be replayed with
`--offline` and no network connection.

## Requirements

- Java 17 or newer
- Maven 3.8 or newer
- An Anthropic API key (only for the online AI pass)

## Setup

```bash
export ANTHROPIC_API_KEY="your-key-here"
mvn clean package
```

The API key is read from the environment and is never committed to the
repository.

## Usage

```bash
# Full analysis: classic pass + AI pass + comparative report
java -jar target/analyzer.jar samples/Buggy.java

# Replay the cached AI response, no network needed
java -jar target/analyzer.jar samples/Buggy.java --offline

# Deterministic pass only
java -jar target/analyzer.jar samples/Buggy.java --classic-only
```

## Sample file

`samples/Buggy.java` contains planted defects of both families: syntactic ones
the classic rules catch, and semantic ones only the AI pass reports.

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
└── report/ComparativeReport.java   Side-by-side console report
```

## License

Academic project. Free to read, fork and learn from.
