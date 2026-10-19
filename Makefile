# Makefile for Google Hash Code 2017 - Group K Solver & Judge

ifeq ($(OS),Windows_NT)
    SHELL := cmd.exe
    .SHELLFLAGS := /c
    EXE := .exe
    MKDIR = if not exist $(1) mkdir $(1)
    RMDIR = if exist $(1) rmdir /s /q $(1)
    RM = if exist $(1) del /f /q $(1)
    RUN_JUDGE = $(TARGET_JUDGE)
else
    SHELL := /bin/sh
    EXE :=
    MKDIR = mkdir -p $(1)
    RMDIR = rm -rf $(1)
    RM = rm -f $(1)
    RUN_JUDGE = ./$(TARGET_JUDGE)
endif

JAVAC       = javac
JAVA        = java
CXX         = g++
CXXFLAGS    = -O3

OUT_DIR      = out
OUTPUT_DIR   = output
OUTPUT_K_DIR = output_k
TARGET_JUDGE = judge$(EXE)
INSTANCES_K  = $(sort $(wildcard instances_k/*.txt))

ILS_SECONDS ?= 60
JAVA_OPTS   ?= -Xmx4g -Dils.seconds=$(ILS_SECONDS)

.PHONY: all compile judge all-instances judge-all generate dashboard pipeline run clean FORCE

all: compile judge

# Compile Java solver classes
compile:
	@$(call MKDIR,$(OUT_DIR))
	$(JAVAC) -d $(OUT_DIR) src/GoogleHash2017.java src/model/*.java src/io/*.java src/solver/*.java src/solver/impl/*.java src/solver/ils/*.java

# Compile C++ judge
judge: $(TARGET_JUDGE)

$(TARGET_JUDGE): judgeHashCode2017.cpp
	$(CXX) $(CXXFLAGS) judgeHashCode2017.cpp -o $(TARGET_JUDGE)

# Rule to run solver and judge on an instance:
# Usage: make instances/filename.txt or make instances/filename.in
instances/%.txt: FORCE compile judge
	@$(call MKDIR,$(OUTPUT_DIR))
	$(JAVA) $(JAVA_OPTS) -cp $(OUT_DIR) GoogleHash2017 $@ $(OUTPUT_DIR)/$*.txt
	@echo ----------------------------------------
	@echo Running Official Judge for $*
	@$(RUN_JUDGE) $@ $(OUTPUT_DIR)/$*.txt
	@echo ----------------------------------------

instances/%.in: FORCE compile judge
	@$(call MKDIR,$(OUTPUT_DIR))
	$(JAVA) $(JAVA_OPTS) -cp $(OUT_DIR) GoogleHash2017 $@ $(OUTPUT_DIR)/$*.out
	@echo ----------------------------------------
	@echo Running Official Judge for $*
	@$(RUN_JUDGE) $@ $(OUTPUT_DIR)/$*.out
	@echo ----------------------------------------

# Rule for instances in instances_k folder:
# Output is placed in output_k/ as <instance_name>_K.txt
instances_k/%.txt: FORCE compile judge
	@$(call MKDIR,$(OUTPUT_K_DIR))
	$(JAVA) $(JAVA_OPTS) -cp $(OUT_DIR) GoogleHash2017 $@ $(OUTPUT_K_DIR)/$*_K.txt
	@echo ----------------------------------------
	@echo Running Official Judge for $*
	@$(RUN_JUDGE) $@ $(OUTPUT_K_DIR)/$*_K.txt
	@echo ----------------------------------------

all-instances: compile judge $(INSTANCES_K)

# Run the official judge on all instances in instances_k against existing solutions in intances_K / output_k
judge-all: judge
	python tools/judge_all.py

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

# ── Maintenance ────────────────────────────────────────────────────────────────
clean:
	@$(call RMDIR,$(OUT_DIR))
	@$(call RMDIR,$(OUTPUT_DIR))
	@$(call RMDIR,$(OUTPUT_K_DIR))
	@$(call RMDIR,web_cache)
	@$(call RM,$(TARGET_JUDGE))