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

OUT_DIR     = out
OUTPUT_DIR  = output
TARGET_JUDGE = judge$(EXE)

.PHONY: all compile judge clean FORCE

all: compile judge

# Compile Java solver classes
compile:
	@$(call MKDIR,$(OUT_DIR))
	$(JAVAC) -d $(OUT_DIR) src/GoogleHash2017.java src/model/*.java src/io/*.java src/solver/*.java src/solver/impl/*.java

# Compile C++ judge
judge: $(TARGET_JUDGE)

$(TARGET_JUDGE): judgeHashCode2017.cpp
	$(CXX) $(CXXFLAGS) judgeHashCode2017.cpp -o judge

# Rule to run solver and judge on an instance:
# Usage: make instances/filename.in (e.g. make instances/me_at_the_zoo.in)
instances/%.in: FORCE compile judge
	@$(call MKDIR,$(OUTPUT_DIR))
	$(JAVA) -cp $(OUT_DIR) GoogleHash2017 $@ $(OUTPUT_DIR)/$*.out
	@echo ----------------------------------------
	@echo Running Official Judge:
	@.\judge$(EXE) $@ $(OUTPUT_DIR)/$*.out
	@echo ----------------------------------------

clean:
	@$(call RMDIR,$(OUT_DIR))
	@$(call RMDIR,$(OUTPUT_DIR))
	@$(call RM,$(TARGET_JUDGE))
