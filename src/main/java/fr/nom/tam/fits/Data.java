/*
 * This code is part of the Java FITS library developed 1996-2012 by T.A. McGlynn (NASA/GSFC)
 * The code is available in the public domain and may be copied, modified and used
 * by anyone in any fashion for any purpose without restriction. 
 * 
 * No warranty regarding correctness or performance of this code is given or implied.
 * Users may contact the author if they have questions or concerns.
 * 
 * The author would like to thank many who have contributed suggestions, 
 * enhancements and bug fixes including:
 * David Glowacki, R.J. Mathar, Laurent Michel, Guillaume Belanger,
 * Laurent Bourges, Rose Early, Fred Romelfanger, Jorgo Baker, A. Kovacs, V. Forchi, J.C. Segovia,
 * Booth Hartley and Jason Weiss.  
 * I apologize to any contributors whose names may have been inadvertently omitted.
 * 
 *      Tom McGlynn
 */
package fr.nom.tam.fits;

import fr.nom.tam.util.ArrayDataInput;
import fr.nom.tam.util.ArrayDataOutput;
import fr.nom.tam.util.RandomAccess;
import java.io.IOException;

/** This class provides methods to access the data segment of an
 * HDU.
 */
public abstract class Data implements FitsElement {

    /** This is the object which contains the actual data for the HDU.
     * <ul>
     *  <li> For images and primary data this is a simple (but possibly
     *       multi-dimensional) primitive array.  When group data is
     *       supported it will be a possibly multidimensional array
     *       of group objects.
     *  <li> For ASCII data it is a two dimensional Object array where
     *       each of the constituent objects is a primitive array of length 1.
     *  <li> For Binary data it is a two dimensional Object array where
     *       each of the constituent objects is a primitive array of arbitrary
     *       (more or less) dimensionality.
     *  </ul>
     */
    /** The starting location of the data when last read */
    protected long fileOffset = -1;
    /** The size of the data when last read */
    protected long dataSize;
    /** The inputstream used. */
    protected RandomAccess input;

    /** Get the file offset */
    public long getFileOffset() {
        return fileOffset;
    }

    /** Set the fields needed for a re-read */
    protected void setFileOffset(Object o) {
        if (o instanceof RandomAccess) {
            fileOffset = FitsUtil.findOffset(o);
            dataSize = getTrueSize();
            input = (RandomAccess) o;
        }
    }

    /** Write the data -- including any buffering needed
     * @param o  The output stream on which to write the data.
     */
    public abstract void write(ArrayDataOutput o) throws FitsException;

    /** Read a data array into the current object and if needed position
     * to the beginning of the next FITS block.
     * @param i The input data stream
     */
    public abstract void read(ArrayDataInput i) throws FitsException;

    public void rewrite() throws FitsException {

        if (!rewriteable()) {
            throw new FitsException("Illegal attempt to rewrite data");
        }

        FitsUtil.reposition(input, fileOffset);
        write((ArrayDataOutput) input);
        try {
            ((ArrayDataOutput) input).flush();
        } catch (IOException e) {
            throw new FitsException("Error in rewrite flush: " + e);
        }
    }

    public boolean reset() {
        try {
            FitsUtil.reposition(input, fileOffset);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean rewriteable() {
        if (input == null
                || fileOffset < 0
                || (getTrueSize() + 2879) / 2880 != (dataSize + 2879) / 2880) {
            return false;
        } else {
            return true;
        }
    }

    abstract long getTrueSize();

    /** Get the size of the data element in bytes */
    public long getSize() {
        return FitsUtil.addPadding(getTrueSize());
    }

    /** Return the data array object.
     */
    public abstract Object getData() throws FitsException;

    /** Return the non-FITS data object */
    public Object getKernel() throws FitsException {
        return getData();
    }

    /** Modify a header to point to this data
     */
    abstract void fillHeader(Header head) throws FitsException;
}
