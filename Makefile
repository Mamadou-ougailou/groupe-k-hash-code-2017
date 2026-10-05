# Makefile for Google Hash Code 2017 - Group K Solver & Judge

ifeq ($(OS),Windows_NT)
    SHELL := cmd.exe
    .SHELLFLAGS := /c
    EXE := .exe
    MKDIR = if not exist $(1) mkdir $(1)
    RMDIR = if exist $(1) rmdir /s /q $(1)
    RM = if exist $(1) del /f /q $(1)
else
    SHELL := /bin/sh
    EXE :=
    MKDIR = mkdir -p $(1)
    RMDIR = rm -rf $(1)
    RM = rm -f $(1)
endif

JAVAC       = javac
JAVA        = java
CXX         = g++
CXXFLAGS    = -O3

OUT_DIR      = out
OUTPUT_DIR   = output
TARGET_JUDGE = judge$(EXE)

.PHONY: all compile judge generate dashboard pipeline run clean FORCE

all: compile judge

# Compile Java solver classes
compile:
	@$(call MKDIR,$(OUT_DIR))
	$(JAVAC) -d $(OUT_DIR) src/GoogleHash2017.java src/model/*.java src/io/*.java src/solver/*.java src/solver/impl/*.java

# Compile C++ judge
judge: $(TARGET_JUDGE)

$(TARGET_JUDGE): judgeHashCode2017.cpp
	$(CXX) $(CXXFLAGS) judgeHashCode2017.cpp -o $(TARGET_JUDGE)

# Rule to run solver and judge on an instance:
# Usage: make instances/filename.in (e.g. make instances/me_at_the_zoo.in)
instances/%.in: FORCE compile judge
	@$(call MKDIR,$(OUTPUT_DIR))
	$(JAVA) -cp $(OUT_DIR) GoogleHash2017 $@ $(OUTPUT_DIR)/$*.out
	@echo ----------------------------------------
	@echo Running Official Judge:
	@$(TARGET_JUDGE) $@ $(OUTPUT_DIR)/$*.out
	@echo ----------------------------------------

# ── Python tooling ─────────────────────────────────────────────────────────────

# Generate a custom instance (default: dejaVu generator, seed 42)
# Override: make generate GENERATOR=universalLambda SEED=123
GENERATOR ?= dejaVu
SEED      ?= 42

generate:
	python tools/instance_creator/instanceCreator.py --generator $(GENERATOR) --seed $(SEED)

# Launch the analysis dashboard at http://127.0.0.1:8765
# Must be run from the project root so that `python -m tools.dashboard.dashboard` resolves correctly.
dashboard:
	python -m tools.dashboard.dashboard

# Full pipeline: generate → validate → compile → solve → judge [→ dashboard]
# Override: make pipeline GENERATOR=universalLambda SEED=7 DASHBOARD=--dashboard
DASHBOARD ?=
pipeline:
	python tools/pipeline.py --generator $(GENERATOR) --seed $(SEED) $(DASHBOARD)

# Pipeline on an existing instance (no generation step)
# Usage: make run INSTANCE=instances/me_at_the_zoo.in
# Add DASHBOARD=--dashboard to also open the dashboard.
run:
	python tools/pipeline.py --instance $(INSTANCE) $(DASHBOARD)

# ── Maintenance ────────────────────────────────────────────────────────────────
clean:
	@$(call RMDIR,$(OUT_DIR))
	@$(call RMDIR,$(OUTPUT_DIR))
	@$(call RMDIR,web_cache)
	@$(call RM,$(TARGET_JUDGE))
