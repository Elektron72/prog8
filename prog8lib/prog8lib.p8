; Prog8 internal library routines - always included by the compiler
;
; Written by Irmen de Jong (irmen@razorvine.net) - license: GNU GPL 3.0
;
; indent format: TABS, size=8


~ prog8_lib {
		; note: the following ZP scratch registers must be the same as in c64lib
		memory  ubyte  SCRATCH_ZP1	= $02		; scratch register #1 in ZP
		memory  ubyte  SCRATCH_ZP2	= $03		; scratch register #2 in ZP
		memory  uword  SCRATCH_ZPWORD1	= $fb		; scratch word in ZP ($fb/$fc)
		memory  uword  SCRATCH_ZPWORD2	= $fd		; scratch word in ZP ($fd/$fe)


	%asm {{

; 16-bit rotate right (as opposed to the 6502's usual 17-bit rotate with carry)
; the word is placed in SCRATCH_ZPWORD1
ror2_word	
		lsr  SCRATCH_ZPWORD1+1
		ror  SCRATCH_ZPWORD1
		bcc  +
		lda  SCRATCH_ZPWORD1+1
		ora  #$80
		sta  SCRATCH_ZPWORD1+1
+		rts



; @todo:  stubs for now
; @todo:  move float operations to their own library (only included when floats are enabled)

ub2float
		rts

uw2float
		rts

push_float
		rts

pop_var_float
		rts

copy_float
		rts

inc_var_f
		rts
                
dec_var_f
		rts

div_f
		rts

add_f
		rts

sub_f
		rts

mul_f
		rts

neg_f
		rts

sub_uw
		rts

less_ub
		rts

less_f
		rts

		
func_sin
		rts
func_cos
		rts
func_abs
		rts
func_acos
		rts
func_asin
		rts
func_tan
		rts
func_atan
		rts
func_ln
		rts
func_log2
		rts
func_log10
		rts
func_sqrt
		rts
func_rad
		rts
func_deg
		rts
func_round
		rts
func_floor
		rts
func_ceil
		rts
func_max
		rts
func_min
		rts
func_avg
		rts
func_sum
		rts
func_len
		rts
func_any
		rts
func_all
		rts
func_rnd
		rts
func_rndw
		rts
func_rndf
		rts
func_wrd
		rts
func_uwrd
		rts
func_str2byte
		rts
func_str2ubyte
		rts
func_str2word
		rts
func_str2uword
		rts
func_str2float
		rts
    
	}}
}
