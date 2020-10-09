%import textio
%import syslib
%zeropage basicsafe


main {

    str name


    struct Planet {
        ubyte x
        ubyte y
        str name = "????????"
    }

    sub start() {

        txt.chrout('\n')
    }

    asmsub testX() {
        %asm {{
            stx  _saveX
            lda  #13
            jsr  txt.chrout
            lda  _saveX
            jsr  txt.print_ub
            lda  #13
            jsr  txt.chrout
            ldx  _saveX
            rts
_saveX   .byte 0
        }}
    }
}



