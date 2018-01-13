"""
Programming Language for 6502/6510 microprocessors, codename 'Sick'
This is the assembly code generator (from the parse tree)

Written by Irmen de Jong (irmen@razorvine.net) - license: GNU GPL 3.0
"""

import os
import re
import subprocess
import datetime
from collections import defaultdict
from typing import Dict, TextIO, List, Any
from .plylex import print_bold
from .plyparse import Module, ProgramFormat, Block, Directive, VarDef, Label, Subroutine, AstNode, ZpOptions, \
    InlineAssembly, Return, Register, LiteralValue
from .datatypes import VarType, DataType, to_hex, to_mflpt5, STRING_DATATYPES


class CodeError(Exception):
    pass


class AssemblyGenerator:
    BREAKPOINT_COMMENT_SIGNATURE = "~~~BREAKPOINT~~~"
    BREAKPOINT_COMMENT_DETECTOR = r".(?P<address>\w+)\s+ea\s+nop\s+;\s+{:s}.*".format(BREAKPOINT_COMMENT_SIGNATURE)

    def __init__(self, module: Module) -> None:
        self.module = module
        self.cur_block = None
        self.output = None      # type: TextIO

    def p(self, text, *args, **vargs):
        # replace '\v' (vertical tab) char by the actual line indent (2 tabs) and write to the stringIo
        print(text.replace("\v", "\t\t"), *args, file=self.output, **vargs)

    def generate(self, filename: str) -> None:
        with open(filename, "wt") as self.output:
            try:
                self._generate()
            except Exception as x:
                self.output.write(".error \"****** ABORTED DUE TO ERROR: " + str(x) + "\"\n")
                raise

    def _generate(self) -> None:
        self.sanitycheck()
        self.header()
        self.init_vars_and_start()
        self.blocks()
        self.footer()

    def sanitycheck(self):
        start_found = False
        for block, parent in self.module.all_scopes():
            for label in block.nodes:
                if isinstance(label, Label) and label.name == "start" and block.name == "main":
                    start_found = True
                    break
            if start_found:
                break
        if not start_found:
            print_bold("ERROR: program entry point is missing ('start' label in 'main' block)\n")
            raise SystemExit(1)
        all_blocknames = [b.name for b in self.module.scope.filter_nodes(Block)]
        unique_blocknames = set(all_blocknames)
        if len(all_blocknames) != len(unique_blocknames):
            for name in unique_blocknames:
                all_blocknames.remove(name)
            raise CodeError("there are duplicate block names", all_blocknames)
        zpblock = self.module.zeropage()
        if zpblock:
            # ZP block contains no code?
            for stmt in zpblock.scope.nodes:
                if not isinstance(stmt, (Directive, VarDef)):
                    raise CodeError("ZP block can only contain directive and var")

    def header(self):
        self.p("; code generated by il65.py - codename 'Sick'")
        self.p("; source file:", self.module.sourceref.file)
        self.p("; compiled on:", datetime.datetime.now())
        self.p("; output options:", self.module.format, self.module.zp_options)
        self.p("; assembler syntax is for the 64tasm cross-assembler")
        self.p("\n.cpu  '6502'\n.enc  'none'\n")
        assert self.module.address is not None
        if self.module.format in (ProgramFormat.PRG, ProgramFormat.BASIC):
            if self.module.format == ProgramFormat.BASIC:
                if self.module.address != 0x0801:
                    raise CodeError("BASIC output mode must have load address $0801")
                self.p("; ---- basic program with sys call ----")
                self.p("* = " + to_hex(self.module.address))
                year = datetime.datetime.now().year
                self.p("\v.word  (+), {:d}".format(year))
                self.p("\v.null  $9e, format(' %d ', _il65_entrypoint), $3a, $8f, ' il65 by idj'")
                self.p("+\v.word  0")
                self.p("_il65_entrypoint\v; assembly code starts here\n")
            else:
                self.p("; ---- program without sys call ----")
                self.p("* = " + to_hex(self.module.address) + "\n")
        elif self.module.format == ProgramFormat.RAW:
            self.p("; ---- raw assembler program ----")
            self.p("* = " + to_hex(self.module.address) + "\n")

    def init_vars_and_start(self) -> None:
        if self.module.zp_options == ZpOptions.CLOBBER_RESTORE:
            self.p("\vjsr  _il65_save_zeropage")
        self.p("\v; initialize all blocks (reset vars)")
        if self.module.zeropage():
            self.p("\vjsr  ZP._il65_init_block")
        for block in self.module.nodes:
            if isinstance(block, Block) and block.name != "ZP":
                self.p("\vjsr  {}._il65_init_block".format(block.name))
        self.p("\v; call user code")
        if self.module.zp_options == ZpOptions.CLOBBER_RESTORE:
            self.p("\vjsr  {:s}.start".format(self.module.main().label))
            self.p("\vcld")
            self.p("\vjmp  _il65_restore_zeropage\n")
            # include the assembly code for the save/restore zeropage routines
            zprestorefile = os.path.join(os.path.split(__file__)[0], "lib", "restorezp.asm")
            with open(zprestorefile, "rU") as f:
                for line in f.readlines():
                    self.p(line.rstrip("\n"))
        else:
            self.p("\vjmp  {:s}.start".format(self.module.main().label))
        self.p("")

    def blocks(self) -> None:
        zpblock = self.module.zeropage()
        if zpblock:
            # if there's a Zeropage block, it always goes first
            self.cur_block = zpblock    # type: ignore
            self.p("\n; ---- zero page block: '{:s}' ----".format(zpblock.name))
            self.p("; file: '{:s}' src l. {:d}\n".format(zpblock.sourceref.file, zpblock.sourceref.line))
            self.p("{:s}\t.proc\n".format(zpblock.label))
            self.generate_block_init(zpblock)
            self.generate_block_vars(zpblock, True)
            # there's no code in the zero page block.
            self.p("\v.pend\n")
        for block in sorted(self.module.scope.filter_nodes(Block), key=lambda b: b.address or 0):
            if block.name == "ZP":
                continue    # already processed
            self.cur_block = block
            self.p("\n; ---- block: '{:s}' ----".format(block.name))
            self.p("; file: '{:s}' src l. {:d}\n".format(block.sourceref.file, block.sourceref.line))
            if block.address:
                self.p(".cerror * > ${0:04x}, 'block address overlaps by ', *-${0:04x},' bytes'".format(block.address))
                self.p("* = ${:04x}".format(block.address))
            self.p("{:s}\t.proc\n".format(block.label))
            self.generate_block_init(block)
            self.generate_block_vars(block)
            subroutines = list(sub for sub in block.scope.filter_nodes(Subroutine) if sub.address is not None)
            if subroutines:
                # these are (external) subroutines that are defined by address instead of a scope/code
                self.p("; external subroutines")
                for subdef in subroutines:
                    assert subdef.scope is None
                    self.p("\v{:s} = {:s}".format(subdef.name, to_hex(subdef.address)))
                self.p("; end external subroutines\n")
            for stmt in block.scope.nodes:
                if isinstance(stmt, (VarDef, Directive, Subroutine)):
                    continue   # should have been handled already or will be later
                self.generate_statement(stmt)
                if block.name == "main" and isinstance(stmt, Label) and stmt.name == "start":
                    # make sure the main.start routine clears the decimal and carry flags as first steps
                    self.p("\vcld\n\vclc\n\vclv")
            subroutines = list(sub for sub in block.scope.filter_nodes(Subroutine) if sub.address is None)
            if subroutines:
                # these are subroutines that are defined by a scope/code
                self.p("; -- block subroutines")
                for subdef in subroutines:
                    assert subdef.scope is not None
                    self.p("{:s}\v; src l. {:d}".format(subdef.name, subdef.sourceref.line))
                    params = ", ".join("{:s} -> {:s}".format(name or "<unnamed>", registers) for name, registers in subdef.param_spec)
                    returns = ",".join(sorted(register for register in subdef.result_spec if register[-1] != '?'))
                    clobbers = ",".join(sorted(register for register in subdef.result_spec if register[-1] == '?'))
                    self.p("\v; params: {}\n\v; returns: {}   clobbers: {}"
                           .format(params or "-", returns or "-", clobbers or "-"))
                    cur_block = self.cur_block
                    self.cur_block = subdef.scope
                    for stmt in subdef.scope.nodes:
                        if isinstance(stmt, (VarDef, Directive)):
                            continue  # should have been handled already
                        self.generate_statement(stmt)
                    self.cur_block = cur_block
                    self.p("")
                self.p("; -- end block subroutines")
            self.p("\n\v.pend\n")

    def footer(self) -> None:
        self.p("\t.end")

    def output_string(self, value: str, screencodes: bool = False) -> str:
        if len(value) == 1 and screencodes:
            if value[0].isprintable() and ord(value[0]) < 128:
                return "'{:s}'".format(value[0])
            else:
                return str(ord(value[0]))
        result = '"'
        for char in value:
            if char in "{}":
                result += '", {:d}, "'.format(ord(char))
            elif char.isprintable() and ord(char) < 128:
                result += char
            else:
                if screencodes:
                    result += '", {:d}, "'.format(ord(char))
                else:
                    if char == '\f':
                        result += "{clear}"
                    elif char == '\b':
                        result += "{delete}"
                    elif char == '\n':
                        result += "{cr}"
                    elif char == '\r':
                        result += "{down}"
                    elif char == '\t':
                        result += "{tab}"
                    else:
                        result += '", {:d}, "'.format(ord(char))
        return result + '"'

    def generate_block_init(self, block: Block) -> None:
        # generate the block initializer
        # @todo add a block initializer subroutine that can contain custom reset/init code? (static initializer)

        def _memset(varname: str, value: int, size: int) -> None:
            value = value or 0
            self.p("\vlda  #<" + varname)
            self.p("\vsta  il65_lib.SCRATCH_ZPWORD1")
            self.p("\vlda  #>" + varname)
            self.p("\vsta  il65_lib.SCRATCH_ZPWORD1+1")
            self.p("\vlda  #" + to_hex(value))
            self.p("\vldx  #" + to_hex(size))
            self.p("\vjsr  il65_lib.memset")

        def _memsetw(varname: str, value: int, size: int) -> None:
            value = value or 0
            self.p("\vlda  #<" + varname)
            self.p("\vsta  il65_lib.SCRATCH_ZPWORD1")
            self.p("\vlda  #>" + varname)
            self.p("\vsta  il65_lib.SCRATCH_ZPWORD1+1")
            self.p("\vlda  #<" + to_hex(value))
            self.p("\vldy  #>" + to_hex(value))
            self.p("\vldx  #" + to_hex(size))
            self.p("\vjsr  il65_lib.memsetw")

        self.p("_il65_init_block\v; (re)set vars to initial values")
        float_inits = {}
        string_inits = []
        prev_value_a, prev_value_x = None, None
        vars_by_datatype = defaultdict(list)  # type: Dict[DataType, List[VarDef]]
        for vardef in block.scope.filter_nodes(VarDef):
            if vardef.vartype == VarType.VAR:
                vars_by_datatype[vardef.datatype].append(vardef)
        for bytevar in sorted(vars_by_datatype[DataType.BYTE], key=lambda vd: vd.value):
            if bytevar.value != prev_value_a:
                self.p("\vlda  #${:02x}".format(bytevar.value))
                prev_value_a = bytevar.value
            self.p("\vsta  {:s}".format(bytevar.name))
        for wordvar in sorted(vars_by_datatype[DataType.WORD], key=lambda vd: vd.value):
            v_hi, v_lo = divmod(wordvar.value, 256)
            if v_hi != prev_value_a:
                self.p("\vlda  #${:02x}".format(v_hi))
                prev_value_a = v_hi
            if v_lo != prev_value_x:
                self.p("\vldx  #${:02x}".format(v_lo))
                prev_value_x = v_lo
            self.p("\vsta  {:s}".format(wordvar.name))
            self.p("\vstx  {:s}+1".format(wordvar.name))
        for floatvar in vars_by_datatype[DataType.FLOAT]:
            fpbytes = to_mflpt5(floatvar.value)  # type: ignore
            float_inits[floatvar.name] = (floatvar.name, fpbytes, floatvar.value)
        for arrayvar in vars_by_datatype[DataType.BYTEARRAY]:
            _memset(arrayvar.name, arrayvar.value, arrayvar.size[0])
        for arrayvar in vars_by_datatype[DataType.WORDARRAY]:
            _memsetw(arrayvar.name, arrayvar.value, arrayvar.size[0])
        for arrayvar in vars_by_datatype[DataType.MATRIX]:
            _memset(arrayvar.name, arrayvar.value, arrayvar.size[0] * arrayvar.size[1])
        # @todo string datatype inits with 1 memcopy
        if float_inits:
            self.p("\vldx  #4")
            self.p("-")
            for varname, (vname, b, fv) in sorted(float_inits.items()):
                self.p("\vlda  _init_float_{:s},x".format(varname))
                self.p("\vsta  {:s},x".format(vname))
            self.p("\vdex")
            self.p("\vbpl  -")
        self.p("\vrts\n")
        for varname, (vname, fpbytes, fpvalue) in sorted(float_inits.items()):
            self.p("_init_float_{:s}\t\t.byte  ${:02x}, ${:02x}, ${:02x}, ${:02x}, ${:02x}\t; {}".format(varname, *fpbytes, fpvalue))
        if string_inits:
            self.p("_init_strings_start")
            for svar in sorted(string_inits, key=lambda v: v.name):
                self._generate_string_var(svar, init=True)
            self.p("_init_strings_size = * - _init_strings_start")
        self.p("")

    def _numeric_value_str(self, value: Any, as_hex: bool=False) -> str:
        if isinstance(value, bool):
            return "1" if value else "0"
        if isinstance(value, int):
            if as_hex:
                return to_hex(value)
            return str(value)
        if isinstance(value, (int, float)):
            if as_hex:
                raise TypeError("cannot output float as hex")
            return str(value)
        raise TypeError("no numeric representation possible", value)

    def generate_block_vars(self, block: Block, zeropage: bool=False) -> None:
        # Generate the block variable storage.
        # The memory bytes of the allocated variables is set to zero (so it compresses very well),
        # their actual starting values are set by the block init code.
        vars_by_vartype = defaultdict(list)  # type: Dict[VarType, List[VarDef]]
        for vardef in block.scope.filter_nodes(VarDef):
            vars_by_vartype[vardef.vartype].append(vardef)
        self.p("; constants")
        for vardef in vars_by_vartype.get(VarType.CONST, []):
            if vardef.datatype == DataType.FLOAT:
                self.p("\v{:s} = {}".format(vardef.name, self._numeric_value_str(vardef.value)))
            elif vardef.datatype in (DataType.BYTE, DataType.WORD):
                self.p("\v{:s} = {:s}".format(vardef.name, self._numeric_value_str(vardef.value, True)))
            elif vardef.datatype in STRING_DATATYPES:
                # a const string is just a string variable in the generated assembly
                self._generate_string_var(vardef)
            else:
                raise CodeError("invalid const type", vardef)
        self.p("; memory mapped variables")
        for vardef in vars_by_vartype.get(VarType.MEMORY, []):
            # create a definition for variables at a specific place in memory (memory-mapped)
            if vardef.datatype in (DataType.BYTE, DataType.WORD, DataType.FLOAT):
                assert vardef.size == [1]
                self.p("\v{:s} = {:s}\t; {:s}".format(vardef.name, to_hex(vardef.value), vardef.datatype.name.lower()))
            elif vardef.datatype == DataType.BYTEARRAY:
                assert len(vardef.size) == 1
                self.p("\v{:s} = {:s}\t; array of {:d} bytes".format(vardef.name, to_hex(vardef.value), vardef.size[0]))
            elif vardef.datatype == DataType.WORDARRAY:
                assert len(vardef.size) == 1
                self.p("\v{:s} = {:s}\t; array of {:d} words".format(vardef.name, to_hex(vardef.value), vardef.size[0]))
            elif vardef.datatype == DataType.MATRIX:
                assert len(vardef.size) == 2
                self.p("\v{:s} = {:s}\t; matrix of {:d} by {:d} = {:d} bytes"
                       .format(vardef.name, to_hex(vardef.value), vardef.size[0], vardef.size[1], vardef.size[0]*vardef.size[1]))
            else:
                raise CodeError("invalid var type")
        self.p("; normal variables - initial values will be set by init code")
        if zeropage:
            # zeropage uses the zp_address we've allocated, instead of allocating memory here
            for vardef in vars_by_vartype.get(VarType.VAR, []):
                assert vardef.zp_address is not None
                if vardef.datatype in (DataType.WORDARRAY, DataType.BYTEARRAY, DataType.MATRIX):
                    size_str = "size " + str(vardef.size)
                else:
                    size_str = ""
                self.p("\v{:s} = {:s}\t; {:s} {:s}".format(vardef.name, to_hex(vardef.zp_address),
                                                           vardef.datatype.name.lower(), size_str))
        else:
            # create definitions for the variables that takes up empty space and will be initialized at startup
            string_vars = []
            for vardef in vars_by_vartype.get(VarType.VAR, []):
                if vardef.datatype in (DataType.BYTE, DataType.WORD, DataType.FLOAT):
                    assert vardef.size == [1]
                    if vardef.datatype == DataType.BYTE:
                        self.p("{:s}\v.byte  ?".format(vardef.name))
                    elif vardef.datatype == DataType.WORD:
                        self.p("{:s}\v.word  ?".format(vardef.name))
                    elif vardef.datatype == DataType.FLOAT:
                        self.p("{:s}\v.fill  5\t\t; float".format(vardef.name))
                    else:
                        raise CodeError("weird datatype")
                elif vardef.datatype in (DataType.BYTEARRAY, DataType.WORDARRAY):
                    assert len(vardef.size) == 1
                    if vardef.datatype == DataType.BYTEARRAY:
                        self.p("{:s}\v.fill  {:d}\t\t; bytearray".format(vardef.name, vardef.size[0]))
                    elif vardef.datatype == DataType.WORDARRAY:
                        self.p("{:s}\v.fill  {:d}*2\t\t; wordarray".format(vardef.name, vardef.size[0]))
                    else:
                        raise CodeError("invalid datatype", vardef.datatype)
                elif vardef.datatype == DataType.MATRIX:
                    assert len(vardef.size) == 2
                    self.p("{:s}\v.fill  {:d}\t\t; matrix {:d}*{:d} bytes"
                           .format(vardef.name, vardef.size[0] * vardef.size[1], vardef.size[0], vardef.size[1]))
                elif vardef.datatype in STRING_DATATYPES:
                    string_vars.append(vardef)
                else:
                    raise CodeError("unknown variable type " + str(vardef.datatype))
            if string_vars:
                self.p("il65_string_vars_start")
                for svar in sorted(string_vars, key=lambda v: v.name):      # must be the same order as in the init routine!!!
                    self.p("{:s}\v.fill  {:d}+1\t\t; {}".format(svar.name, len(svar.value), svar.datatype.name.lower()))
        self.p("")

    def _generate_string_var(self, vardef: VarDef, init: bool=False) -> None:
        prefix = "_init_str_" if init else ""
        if vardef.datatype == DataType.STRING:
            # 0-terminated string
            self.p("{:s}{:s}\n\v.null  {:s}".format(prefix, vardef.name, self.output_string(str(vardef.value))))
        elif vardef.datatype == DataType.STRING_P:
            # pascal string
            self.p("{:s}{:s}\n\v.ptext  {:s}".format(prefix, vardef.name, self.output_string(str(vardef.value))))
        elif vardef.datatype == DataType.STRING_S:
            # 0-terminated string in screencode encoding
            self.p(".enc  'screen'")
            self.p("{:s}{:s}\n\v.null  {:s}".format(prefix, vardef.name, self.output_string(str(vardef.value), True)))
            self.p(".enc  'none'")
        elif vardef.datatype == DataType.STRING_PS:
            # 0-terminated pascal string in screencode encoding
            self.p(".enc  'screen'")
            self.p("{:s}{:s}n\v.ptext  {:s}".format(prefix, vardef.name, self.output_string(str(vardef.value), True)))
            self.p(".enc  'none'")

    def generate_statement(self, stmt: AstNode) -> None:
        if isinstance(stmt, Label):
            self.p("\n{:s}\v\t\t; {:s}".format(stmt.name, stmt.lineref))
        elif isinstance(stmt, Return):
            if stmt.value_A:
                self.generate_assignment(Register(name="A", sourceref=stmt.sourceref), '=', stmt.value_A)   # type: ignore
            if stmt.value_X:
                self.generate_assignment(Register(name="X", sourceref=stmt.sourceref), '=', stmt.value_X)   # type: ignore
            if stmt.value_Y:
                self.generate_assignment(Register(name="Y", sourceref=stmt.sourceref), '=', stmt.value_Y)   # type: ignore
            self.p("\vrts")
        elif isinstance(stmt, InlineAssembly):
            self.p("\n\v; inline asm, " + stmt.lineref)
            self.p(stmt.assembly)
            self.p("\v; end inline asm, " + stmt.lineref + "\n")
        else:
            self.p("\vrts; " + str(stmt))   # @todo rest of the statement nodes

    def generate_assignment(self, lvalue: AstNode, operator: str, rvalue: Any) -> None:
        assert rvalue is not None
        if isinstance(rvalue, LiteralValue):
            rvalue = rvalue.value
        print("ASSIGN", lvalue, lvalue.datatype, operator, rvalue)    # @todo


class Assembler64Tass:
    def __init__(self, format: ProgramFormat) -> None:
        self.format = format

    def assemble(self, inputfilename: str, outputfilename: str) -> None:
        args = ["64tass", "--ascii", "--case-sensitive", "-Wall", "-Wno-strict-bool",
                "--dump-labels", "--vice-labels", "-l", outputfilename+".vice-mon-list",
                "-L", outputfilename+".final-asm", "--no-monitor", "--output", outputfilename, inputfilename]
        if self.format in (ProgramFormat.PRG, ProgramFormat.BASIC):
            args.append("--cbm-prg")
        elif self.format == ProgramFormat.RAW:
            args.append("--nostart")
        else:
            raise CodeError("don't know how to create format "+str(self.format))
        try:
            if self.format == ProgramFormat.PRG:
                print("\nCreating C-64 prg.")
            elif self.format == ProgramFormat.RAW:
                print("\nCreating raw binary.")
            try:
                subprocess.check_call(args)
            except FileNotFoundError as x:
                raise SystemExit("ERROR: cannot run assembler program: "+str(x))
        except subprocess.CalledProcessError as x:
            raise SystemExit("assembler failed with returncode " + str(x.returncode))

    def generate_breakpoint_list(self, program_filename: str) -> str:
        breakpoints = []
        with open(program_filename + ".final-asm", "rU") as f:
            for line in f:
                match = re.fullmatch(AssemblyGenerator.BREAKPOINT_COMMENT_DETECTOR, line, re.DOTALL)
                if match:
                    breakpoints.append("$" + match.group("address"))
        cmdfile = program_filename + ".vice-mon-list"
        with open(cmdfile, "at") as f:
            print("; vice monitor breakpoint list now follows", file=f)
            print("; {:d} breakpoints have been defined here".format(len(breakpoints)), file=f)
            print("del", file=f)
            for b in breakpoints:
                print("break", b, file=f)
        return cmdfile
