%target cx16

; Bitmap pixel graphics module for the CommanderX16
; Custom routines to use the full-screen 640x480 and 320x240 screen modes.
; This only works on the Cx16. No text layer is currently shown, text can be drawn as part of the bitmap itself.
; Note: for compatible graphics code that words on C64 too, use the "graphics" module instead.
; Note: there is no color palette manipulation here, you have to do that yourself or use the "palette" module.

; TODO can we make a FB vector table and emulation routines for the Cx16s' GRAPH_init() call? to replace the builtin 320x200 fb driver?

gfx2 {

    ; read-only control variables:
    ubyte active_mode = 255
    uword width = 0
    uword height = 0
    ubyte bpp = 0
    ubyte monochrome_dont_stipple_flag = false            ; set to false to enable stippling mode in monochrome displaymodes

    sub screen_mode(ubyte mode) {
        ; mode 0 = bitmap 320 x 240 x 1c monochrome
        ; mode 1 = bitmap 320 x 240 x 256c
        ; mode 128 = bitmap 640 x 480 x 1c monochrome
        ; ...other modes?

        ; copy the lower-case charset to the upper part of the vram, so we can use it later to plot text

        when mode {
            0 -> {
                ; 320 x 240 x 1c
                cx16.VERA_DC_VIDEO = (cx16.VERA_DC_VIDEO & %11001111) | %00100000      ; enable only layer 1
                cx16.VERA_DC_HSCALE = 64
                cx16.VERA_DC_VSCALE = 64
                cx16.VERA_L1_CONFIG = %00000100
                cx16.VERA_L1_MAPBASE = 0
                cx16.VERA_L1_TILEBASE = 0
                width = 320
                height = 240
                bpp = 1
            }
            1 -> {
                ; 320 x 240 x 256c
                cx16.VERA_DC_VIDEO = (cx16.VERA_DC_VIDEO & %11001111) | %00100000      ; enable only layer 1
                cx16.VERA_DC_HSCALE = 64
                cx16.VERA_DC_VSCALE = 64
                cx16.VERA_L1_CONFIG = %00000111
                cx16.VERA_L1_MAPBASE = 0
                cx16.VERA_L1_TILEBASE = 0
                width = 320
                height = 240
                bpp = 8
            }
            128 -> {
                ; 640 x 480 x 1c
                cx16.VERA_DC_VIDEO = (cx16.VERA_DC_VIDEO & %11001111) | %00100000      ; enable only layer 1
                cx16.VERA_DC_HSCALE = 128
                cx16.VERA_DC_VSCALE = 128
                cx16.VERA_L1_CONFIG = %00000100
                cx16.VERA_L1_MAPBASE = 0
                cx16.VERA_L1_TILEBASE = %00000001
                width = 640
                height = 480
                bpp = 1
            }
            255 -> {
                ; back to default text mode and colors
                cx16.VERA_CTRL = %10000000      ; reset VERA and palette
                c64.CINT()      ; back to text mode
                width = 0
                height = 0
                bpp = 0
            }
        }

        active_mode = mode
        if bpp
            clear_screen()
    }

    sub clear_screen() {
        monochrome_stipple(false)
        position(0, 0)
        when active_mode {
            0 -> {
                ; 320 x 240 x 1c
                repeat 240/2/8
                    cs_innerloop640()
            }
            1 -> {
                ; 320 x 240 x 256c
                repeat 240/2
                    cs_innerloop640()
            }
            128 -> {
                ; 640 x 480 x 1c
                repeat 480/8
                    cs_innerloop640()
            }
        }
        position(0, 0)
    }

    sub monochrome_stipple(ubyte enable) {
        monochrome_dont_stipple_flag = not enable
    }

    sub rect(uword x, uword y, uword width, uword height, ubyte color) {
        if width==0 or height==0
            return
        horizontal_line(x, y, width, color)
        if height==1
            return
        horizontal_line(x, y+height-1, width, color)
        vertical_line(x, y+1, height-2, color)
        if width==1
            return
        vertical_line(x+width-1, y+1, height-2, color)
    }

    sub fillrect(uword x, uword y, uword width, uword height, ubyte color) {
        if width==0
            return
        repeat height {
            horizontal_line(x, y, width, color)
            y++
        }
    }

    sub horizontal_line(uword x, uword y, uword length, ubyte color) {
        if length==0
            return
        when active_mode {
            1 -> {
                ; 8bpp mode
                position(x, y)
                %asm {{
                    lda  color
                    phx
                    ldx  length+1
                    beq  +
                    ldy  #0
-                   sta  cx16.VERA_DATA0
                    iny
                    bne  -
                    dex
                    bne  -
+                   ldy  length     ; remaining
                    beq  +
-                   sta  cx16.VERA_DATA0
                    dey
                    bne  -
+                   plx
                }}
            }
            0, 128 -> {
                ; 1 bpp mode
                ubyte separate_pixels = (8-lsb(x)) & 7
                if separate_pixels as uword > length
                    separate_pixels = lsb(length)
                repeat separate_pixels {
                    ; this could be  optimized by setting this byte in 1 go but probably not worth it due to code size
                    plot(x, y, color)
                    x++
                }
                length -= separate_pixels
                if length {
                    position(x, y)
                    separate_pixels = lsb(length) & 7
                    x += length & $fff8
                    %asm {{
                        lsr  length+1
                        ror  length
                        lsr  length+1
                        ror  length
                        lsr  length+1
                        ror  length
                        lda  color
                        bne  +
                        ldy  #0     ; black
                        bra  _loop
+                       lda  monochrome_dont_stipple_flag
                        beq  _stipple
                        ldy  #255       ; don't stipple
                        bra  _loop
_stipple                lda  y
                        and  #1         ; determine stipple pattern to use
                        bne  +
                        ldy  #%01010101
                        bra  _loop
+                       ldy  #%10101010
_loop                   lda  length
                        ora  length+1
                        beq  _done
                        sty  cx16.VERA_DATA0
                        lda  length
                        bne  +
                        dec  length+1
+                       dec  length
                        bra  _loop
_done
                    }}
                    repeat separate_pixels {
                        ; this could be  optimized by setting this byte in 1 go but probably not worth it due to code size
                        plot(x, y, color)
                        x++
                    }
                }
                cx16.VERA_ADDR_H = (cx16.VERA_ADDR_H & %00000111)   ; vera auto-increment off again
            }
        }
    }

    sub vertical_line(uword x, uword y, uword height, ubyte color) {
        position(x,y)
        if active_mode==1 {
            ; set vera auto-increment to 320 pixel increment (=next line)
            cx16.VERA_ADDR_H = (cx16.VERA_ADDR_H & %00000111) | (14<<4)
            %asm {{
                ldy  height
                beq  +
                lda  color
-               sta  cx16.VERA_DATA0
                dey
                bne  -
+
            }}
            return
        }

        ; note for the 1 bpp modes we can't use vera's auto increment mode because we have to 'or' the pixel data in place.
        cx16.VERA_ADDR_H = (cx16.VERA_ADDR_H & %00000111)   ; no auto advance
        cx16.r15 = gfx2.plot.bits[x as ubyte & 7]           ; bitmask
        if active_mode>=128
            cx16.r14 = 640/8
        else
            cx16.r14 = 320/8
        if color {
            if monochrome_dont_stipple_flag {
                repeat height {
                    %asm {{
                        lda  cx16.VERA_DATA0
                        ora  cx16.r15
                        sta  cx16.VERA_DATA0
                        lda  cx16.VERA_ADDR_L
                        clc
                        adc  cx16.r14                 ; advance vera ptr to go to the next line
                        sta  cx16.VERA_ADDR_L
                        lda  cx16.VERA_ADDR_M
                        adc  #0
                        sta  cx16.VERA_ADDR_M
                        ; lda  cx16.VERA_ADDR_H     ; the bitmap size is small enough to not have to deal with the _H part.
                        ; adc  #0
                        ; sta  cx16.VERA_ADDR_H
                    }}
                }
            } else {
                ; stippling.
                height = (height+1)/2
                %asm {{
                    lda x
                    eor y
                    and #1
                    bne +
                    lda  cx16.VERA_ADDR_L
                    clc
                    adc  cx16.r14                ; advance vera ptr to go to the next line for correct stipple pattern
                    sta  cx16.VERA_ADDR_L
                    lda  cx16.VERA_ADDR_M
                    adc  #0
                    sta  cx16.VERA_ADDR_M
+
                    asl  cx16.r14
                    ldy  height
                    beq  +
-                   lda  cx16.VERA_DATA0
                    ora  cx16.r15
                    sta  cx16.VERA_DATA0
                    lda  cx16.VERA_ADDR_L
                    clc
                    adc  cx16.r14               ; advance vera data ptr to go to the next-next line
                    sta  cx16.VERA_ADDR_L
                    lda  cx16.VERA_ADDR_M
                    adc  #0
                    sta  cx16.VERA_ADDR_M
                    ; lda  cx16.VERA_ADDR_H      ; the bitmap size is small enough to not have to deal with the _H part.
                    ; adc  #0
                    ; sta  cx16.VERA_ADDR_H
                    dey
                    bne  -
+
                }}
            }
        } else {
            cx16.r15 = ~cx16.r15
            repeat height {
                %asm {{
                    lda  cx16.VERA_DATA0
                    and  cx16.r15
                    sta  cx16.VERA_DATA0
                    lda  cx16.VERA_ADDR_L
                    clc
                    adc  cx16.r14             ; advance vera data ptr to go to the next line
                    sta  cx16.VERA_ADDR_L
                    lda  cx16.VERA_ADDR_M
                    adc  #0
                    sta  cx16.VERA_ADDR_M
                    ; lda  cx16.VERA_ADDR_H      ; the bitmap size is small enough to not have to deal with the _H part.
                    ; adc  #0
                    ; sta  cx16.VERA_ADDR_H
                }}
            }
        }
    }

    sub line(uword @zp x1, uword @zp y1, uword @zp x2, uword @zp y2, ubyte color) {
        ; Bresenham algorithm.
        ; This code special-cases various quadrant loops to allow simple ++ and -- operations.
        ; TODO rewrite this in optimized assembly
        if y1>y2 {
            ; make sure dy is always positive to have only 4 instead of 8 special cases
            swap(x1, x2)
            swap(y1, y2)
        }
        word @zp dx = x2-x1 as word
        word @zp dy = y2-y1 as word

        if dx==0 {
            vertical_line(x1, y1, abs(dy)+1 as uword, color)
            return
        }
        if dy==0 {
            if x1>x2
                x1=x2
            horizontal_line(x1, y1, abs(dx)+1 as uword, color)
            return
        }

        word @zp d = 0
        ubyte positive_ix = true
        if dx < 0 {
            dx = -dx
            positive_ix = false
        }
        dx *= 2
        dy *= 2
        cx16.r14 = x1       ; internal plot X

        if dx >= dy {
            if positive_ix {
                repeat {
                    plot(cx16.r14, y1, color)
                    if cx16.r14==x2
                        return
                    cx16.r14++
                    d += dy
                    if d > dx {
                        y1++
                        d -= dx
                    }
                }
            } else {
                repeat {
                    plot(cx16.r14, y1, color)
                    if cx16.r14==x2
                        return
                    cx16.r14--
                    d += dy
                    if d > dx {
                        y1++
                        d -= dx
                    }
                }
            }
        }
        else {
            if positive_ix {
                repeat {
                    plot(cx16.r14, y1, color)
                    if y1 == y2
                        return
                    y1++
                    d += dx
                    if d > dy {
                        cx16.r14++
                        d -= dy
                    }
                }
            } else {
                repeat {
                    plot(cx16.r14, y1, color)
                    if y1 == y2
                        return
                    y1++
                    d += dx
                    if d > dy {
                        cx16.r14--
                        d -= dy
                    }
                }
            }
        }
    }

    sub circle(uword @zp xcenter, uword @zp ycenter, ubyte radius, ubyte color) {
        ; Midpoint algorithm.
        if radius==0
            return

        ubyte @zp xx = radius
        ubyte @zp yy = 0
        word @zp decisionOver2 = (1 as word)-xx
        ; R14 = internal plot X
        ; R15 = internal plot Y

        while xx>=yy {
            cx16.r14 = xcenter + xx
            cx16.r15 = ycenter + yy
            plot(cx16.r14, cx16.r15, color)
            cx16.r14 = xcenter - xx
            plot(cx16.r14, cx16.r15, color)
            cx16.r14 = xcenter + xx
            cx16.r15 = ycenter - yy
            plot(cx16.r14, cx16.r15, color)
            cx16.r14 = xcenter - xx
            plot(cx16.r14, cx16.r15, color)
            cx16.r14 = xcenter + yy
            cx16.r15 = ycenter + xx
            plot(cx16.r14, cx16.r15, color)
            cx16.r14 = xcenter - yy
            plot(cx16.r14, cx16.r15, color)
            cx16.r14 = xcenter + yy
            cx16.r15 = ycenter - xx
            plot(cx16.r14, cx16.r15, color)
            cx16.r14 = xcenter - yy
            plot(cx16.r14, cx16.r15, color)

            yy++
            if decisionOver2<=0
                decisionOver2 += (yy as word)*2+1
            else {
                xx--
                decisionOver2 += (yy as word -xx)*2+1
            }
        }
    }

    sub disc(uword @zp xcenter, uword @zp ycenter, ubyte @zp radius, ubyte color) {
        ; Midpoint algorithm, filled
        if radius==0
            return
        ubyte @zp yy = 0
        word @zp decisionOver2 = (1 as word)-radius

        while radius>=yy {
            horizontal_line(xcenter-radius, ycenter+yy, radius*$0002+1, color)
            horizontal_line(xcenter-radius, ycenter-yy, radius*$0002+1, color)
            horizontal_line(xcenter-yy, ycenter+radius, yy*$0002+1, color)
            horizontal_line(xcenter-yy, ycenter-radius, yy*$0002+1, color)
            yy++
            if decisionOver2<=0
                decisionOver2 += (yy as word)*2+1
            else {
                radius--
                decisionOver2 += (yy as word -radius)*2+1
            }
        }
    }

    sub plot(uword @zp x, uword y, ubyte color) {
        ubyte[8] bits = [128, 64, 32, 16, 8, 4, 2, 1]
        uword addr
        ubyte value

        when active_mode {
            0 -> {
                %asm {{
                    lda  x
                    eor  y
                    ora  monochrome_dont_stipple_flag
                    and  #1
                }}
                if_nz {
                    addr = x/8 + y*(320/8)
                    value = bits[lsb(x)&7]
                    if color
                        cx16.vpoke_or(0, addr, value)
                    else {
                        value = ~value
                        cx16.vpoke_and(0, addr, value)
                    }
                }
            }
            128 -> {
                %asm {{
                    lda  x
                    eor  y
                    ora  monochrome_dont_stipple_flag
                    and  #1
                }}
                if_nz {
                    addr = x/8 + y*(640/8)
                    value = bits[lsb(x)&7]
                    if color
                        cx16.vpoke_or(0, addr, value)
                    else {
                        value = ~value
                        cx16.vpoke_and(0, addr, value)
                    }
                }
            }
            1 -> {
                void addr_mul_320_add_24(y, x)      ; 24 bits result is in r0 and r1L
                value = lsb(cx16.r1)
                cx16.vpoke(value, cx16.r0, color)
                ; activate vera auto-increment mode so next_pixel() can be used after this
                cx16.VERA_ADDR_H = (cx16.VERA_ADDR_H & %00000111) | %00010000
                color = cx16.VERA_DATA0
            }
        }
    }

    sub position(uword @zp x, uword y) {
        when active_mode {
            0 -> {
                cx16.r0 = y*(320/8) + x/8
                cx16.vaddr(0, cx16.r0, 0, 1)
            }
            128 -> {
                cx16.r0 = y*(640/8) + x/8
                cx16.vaddr(0, cx16.r0, 0, 1)
            }
            1 -> {
                void addr_mul_320_add_24(y, x)      ; 24 bits result is in r0 and r1L
                ubyte bank = lsb(cx16.r1)
                cx16.vaddr(bank, cx16.r0, 0, 1)
            }
        }
    }

    inline asmsub next_pixel(ubyte color @A) {
        ; -- sets the next pixel byte to the graphics chip.
        ;    for 8 bpp screens this will plot 1 pixel.
        ;    for 1 bpp screens it will plot 8 pixels at once (color = bit pattern).
        %asm {{
            sta  cx16.VERA_DATA0
        }}
    }

    asmsub next_pixels(uword pixels @AY, uword amount @R0) {
        ; -- sets the next bunch of pixels from a prepared array of bytes.
        ;    for 8 bpp screens this will plot 1 pixel per byte.
        ;    for 1 bpp screens it will plot 8 pixels at once (colors are the bit patterns per byte).
        %asm {{
            phx
            sta  P8ZP_SCRATCH_W1
            sty  P8ZP_SCRATCH_W1+1
            ldx  cx16.r0+1
            beq  +
            ldy  #0
-           lda  (P8ZP_SCRATCH_W1),y
            sta  cx16.VERA_DATA0
            iny
            bne  -
            inc  P8ZP_SCRATCH_W1+1       ; next page of 256 pixels
            dex
            bne  -

+           ldx  cx16.r0           ; remaining pixels
            beq  +
            ldy  #0
-           lda  (P8ZP_SCRATCH_W1),y
            sta  cx16.VERA_DATA0
            iny
            dex
            bne  -
+           plx
        }}
    }

    asmsub set_8_pixels_from_bits(ubyte bits @R0, ubyte oncolor @A, ubyte offcolor @Y) {
        ; this is only useful in 256 color mode where one pixel equals one byte value.
        %asm {{
            phx
            ldx  #8
-           asl  cx16.r0
            bcc  +
            sta  cx16.VERA_DATA0
            bra  ++
+           sty  cx16.VERA_DATA0
+           dex
            bne  -
            plx
            rts
        }}
    }

    const ubyte charset_orig_bank = $0
    const uword charset_orig_addr = $f800        ; in bank 0, so $0f800
    const ubyte charset_bank = $1
    const uword charset_addr = $f000       ; in bank 1, so $1f000

    sub text_charset(ubyte charset) {
        ; -- make a copy of the selected character set to use with text()
        ;    the charset number is the same as for the cx16.screen_set_charset() ROM function.
        ;    1 = ISO charset, 2 = PETSCII uppercase+graphs, 3= PETSCII uppercase+lowercase.
        cx16.screen_set_charset(charset, 0)
        cx16.vaddr(charset_orig_bank, charset_orig_addr, 0, 1)
        cx16.vaddr(charset_bank, charset_addr, 1, 1)
        repeat 256*8 {
            cx16.VERA_DATA1 = cx16.VERA_DATA0
        }
    }

    sub text(uword @zp x, uword y, ubyte color, uword sctextptr) {
        ; -- Write some text at the given pixel position. The text string must be in screencode encoding (not petscii!).
        ;    You must also have called text_charset() first to select and prepare the character set to use.
        ;    NOTE: in monochrome (1bpp) screen modes, x position is currently constrained to mulitples of 8 !
        uword chardataptr
        when active_mode {
            0, 128 -> {
                ; 1-bitplane modes
                cx16.r2 = 40
                if active_mode>=128
                    cx16.r2 = 80
                while @(sctextptr) {
                    chardataptr = charset_addr + (@(sctextptr) as uword)*8
                    cx16.vaddr(charset_bank, chardataptr, 1, 1)
                    position(x,y)
                    %asm {{
                        lda  cx16.VERA_ADDR_H
                        and  #%111              ; don't auto-increment, we have to do that manually because of the ora
                        sta  cx16.VERA_ADDR_H
                        lda  color
                        sta  P8ZP_SCRATCH_B1
                        ldy  #8
-                       lda  P8ZP_SCRATCH_B1
                        bne  +                  ; white color, plot normally
                        lda  cx16.VERA_DATA1
                        eor  #255               ; black color, keep only the other pixels
                        and  cx16.VERA_DATA0
                        bra  ++
+                       lda  cx16.VERA_DATA0
                        ora  cx16.VERA_DATA1
+                       sta  cx16.VERA_DATA0
                        lda  cx16.VERA_ADDR_L
                        clc
                        adc  cx16.r2
                        sta  cx16.VERA_ADDR_L
                        bcc  +
                        inc  cx16.VERA_ADDR_M
+                       lda  x
                        clc
                        adc  #1
                        sta  x
                        bcc  +
                        inc  x+1
+                       dey
                        bne  -
                    }}
                    sctextptr++
                }
            }
            1 -> {
                ; 320 x 240 x 256c
                while @(sctextptr) {
                    chardataptr = charset_addr + (@(sctextptr) as uword)*8
                    cx16.vaddr(charset_bank, chardataptr, 1, 1)
                    repeat 8 {
                        position(x,y)
                        y++
                        %asm {{
                            phx
                            ldx  #1
                            lda  cx16.VERA_DATA1
                            sta  P8ZP_SCRATCH_B1
                            ldy  #8
-                           asl  P8ZP_SCRATCH_B1
                            bcc  +
                            stx  cx16.VERA_DATA0    ; write a pixel
                            bra  ++
+                           lda  cx16.VERA_DATA0    ; don't write a pixel, but do advance to the next address
+                           dey
                            bne  -
                            plx
                        }}
                    }
                    x+=8
                    y-=8
                    sctextptr++
                }
            }
        }
    }

    asmsub cs_innerloop640() {
        %asm {{
            ldy  #80
-           stz  cx16.VERA_DATA0
            stz  cx16.VERA_DATA0
            stz  cx16.VERA_DATA0
            stz  cx16.VERA_DATA0
            stz  cx16.VERA_DATA0
            stz  cx16.VERA_DATA0
            stz  cx16.VERA_DATA0
            stz  cx16.VERA_DATA0
            dey
            bne  -
            rts
        }}
    }

    asmsub addr_mul_320_add_24(uword address @R0, uword value @AY) -> uword @R0, ubyte @R1  {
            %asm {{
                sta  P8ZP_SCRATCH_W1
                sty  P8ZP_SCRATCH_W1+1
                lda  cx16.r0
                sta  P8ZP_SCRATCH_B1
                lda  cx16.r0+1
                sta  cx16.r1
                sta  P8ZP_SCRATCH_REG
                lda  cx16.r0
                asl  a
                rol  P8ZP_SCRATCH_REG
                asl  a
                rol  P8ZP_SCRATCH_REG
                asl  a
                rol  P8ZP_SCRATCH_REG
                asl  a
                rol  P8ZP_SCRATCH_REG
                asl  a
                rol  P8ZP_SCRATCH_REG
                asl  a
                rol  P8ZP_SCRATCH_REG
                sta  cx16.r0
                lda  P8ZP_SCRATCH_B1
                clc
                adc  P8ZP_SCRATCH_REG
                sta  cx16.r0+1
                bcc  +
                inc  cx16.r1
+		        ; now add the value to this 24-bits number
                lda  cx16.r0
                clc
                adc  P8ZP_SCRATCH_W1
                sta  cx16.r0
                lda  cx16.r0+1
                adc  P8ZP_SCRATCH_W1+1
                sta  cx16.r0+1
                bcc  +
                inc  cx16.r1
+               lda  cx16.r1
                rts
            }}
        }

}