/* 
 * Copyright (C) 2018 CNRS - JMMC project ( http://www.jmmc.fr )
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
/*******************************************************************************
 * JMMC project ( http://www.jmmc.fr ) - Copyright (C) CNRS.
 ******************************************************************************/
package fr.jmmc.oitools.image;

import fr.jmmc.oitools.fits.FitsTable;
import fr.jmmc.oitools.meta.KeywordMeta;
import fr.jmmc.oitools.meta.Types;
import java.io.Serializable;

/**
 *
 * @author martin
 */
public class UserAppreciationParam extends FitsTable implements Serializable {

    // Define User appreciation keywords
    private final static KeywordMeta KEYWORD_RATING = new KeywordMeta(ImageOiConstants.KEYWORD_RATING, "User rating of the reconstructed image", Types.TYPE_INT);
    private final static  KeywordMeta KEYWORD_COMMENTS = new KeywordMeta(ImageOiConstants.KEYWORD_COMMENTS, "Optional user comments", Types.TYPE_CHAR);
    
    public UserAppreciationParam() {
        super();
        
        // add standard keywords
        addKeywordMeta(KEYWORD_RATING);
        addKeywordMeta(KEYWORD_COMMENTS);
        
        // Set default values
        setNbRows(0);
        setExtVer(1);
        setExtName(ImageOiConstants.EXTNAME_IMAGE_OI_USER_APPRECIATION_PARAM);
    }
    
    /*
     * --- Keywords ------------------------------------------------------------ 
     */
    public int getRating() {
        return getKeywordInt(ImageOiConstants.KEYWORD_RATING);
    }
    
    public void setRating(int rating) {
        setKeywordInt(ImageOiConstants.KEYWORD_RATING, rating);
    }
    
    public String getComments() {
        return getKeyword(ImageOiConstants.KEYWORD_COMMENTS);
    }
    
    public void setComments(String comments) {
        setKeyword(ImageOiConstants.KEYWORD_COMMENTS, comments);
    }
} 
