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
package fr.jmmc.oitools.processing;

import fr.jmmc.oitools.fits.FitsConstants;
import fr.jmmc.oitools.image.FitsImageHDU;
import fr.jmmc.oitools.meta.KeywordMeta;
import fr.jmmc.oitools.meta.OIFitsStandard;
import fr.jmmc.oitools.model.ModelBase;
import static fr.jmmc.oitools.model.ModelBase.UNDEFINED;
import fr.jmmc.oitools.model.NightId;
import fr.jmmc.oitools.model.NightIdMatcher;
import fr.jmmc.oitools.model.OIArray;
import fr.jmmc.oitools.model.OICorr;
import fr.jmmc.oitools.model.OIData;
import fr.jmmc.oitools.model.OIFitsCollection;
import fr.jmmc.oitools.model.OIFitsFile;
import fr.jmmc.oitools.model.OIPrimaryHDU;
import fr.jmmc.oitools.model.OITarget;
import fr.jmmc.oitools.model.OIWavelength;
import fr.jmmc.oitools.model.StaNamesDir;
import fr.jmmc.oitools.model.Target;
import fr.jmmc.oitools.model.TargetManager;
import fr.jmmc.oitools.model.range.Range;
import fr.jmmc.oitools.util.OITableComparator;
import fr.nom.tam.fits.FitsDate;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Processing class to merge the given OIFitsCollection into a single OIFitsFile instance
 *
 * @author jammetv
 */
public final class Merger {

    private static final Logger logger = Logger.getLogger(Merger.class.getName());

    private static final Short UNDEFINED_SHORT = Short.valueOf(ModelBase.UNDEFINED_SHORT);

    /**
     * Utility class
     */
    private Merger() {
        super();
    }

    /**
     * Merge the given OIFitsFile instances.
     *
     * @param oiFitsToMerge OIFitsFile instances
     * @return new OIFitsFile instance
     * @throws IllegalArgumentException
     */
    public static OIFitsFile process(final OIFitsFile... oiFitsToMerge) throws IllegalArgumentException {
        if (oiFitsToMerge == null || oiFitsToMerge.length < 1) {
            throw new IllegalArgumentException("Merge: Missing OIFits inputs");
        }
        final OIFitsCollection oiFitsCollection = OIFitsCollection.create(oiFitsToMerge);

        return process(oiFitsCollection);
    }

    /**
     * Merge the given OIFitsCollection
     *
     * @param oiFitsCollection OIFits collection
     * @return new OIFitsFile instance
     * @throws IllegalArgumentException
     */
    public static OIFitsFile process(final OIFitsCollection oiFitsCollection) throws IllegalArgumentException {
        return process(oiFitsCollection, null, null);
    }

    /**
     * Merge the given OIFitsCollection with the given Selector
     *
     * @param oiFitsCollection OIFits collection
     * @param selector optional Selector instance to filter content
     * @return new OIFitsFile instance
     * @throws IllegalArgumentException
     */
    public static OIFitsFile process(final OIFitsCollection oiFitsCollection, final Selector selector) throws IllegalArgumentException {
        return process(oiFitsCollection, selector, null);
    }

    /**
     * Merge of OIFitsFile structure.
     *
     * @param oiFitsCollection OIFits collection
     * @param selector optional Selector instance to filter content
     * @param std OIFits standard for the output OIFitsFile
     * @return new OIFitsFile instance
     * @throws IllegalArgumentException
     */
    public static OIFitsFile process(final OIFitsCollection oiFitsCollection, final Selector selector, final OIFitsStandard std) throws IllegalArgumentException {
        if (oiFitsCollection == null || oiFitsCollection.isEmpty()) {
            throw new IllegalArgumentException("Merge: Missing OIFits inputs");
        }

        final SelectorResult result = filterData(oiFitsCollection, selector);

        return process(result, std);
    }

    public static OIFitsFile process(final SelectorResult result) {
        return process(result, null);
    }

    public static OIFitsFile process(final SelectorResult result, final OIFitsStandard std) {
        // 1. CreateOIFits anyway
        final OIFitsFile resultFile = createOIFits(std, result);

        if (result != null) {
            final Context ctx = new Context(result, resultFile);

            // 2. Get all referenced OIPrimaryHDU, Target, OIWavelength, OIArray and OICorr in data tables
            collectTables(ctx);

            // 2.1 Process Primary header
            processOIPrimaryHDU(ctx);

            // 3. process Meta Data to prepare mappings:
            // Process OI_TARGET:
            processOITarget(ctx);

            // Process OI_WAVELENGTH tables
            processOIWavelengths(ctx);

            // Process OI_ARRAY tables 
            processOIArrays(ctx);

            // Specific to OIFits V2
            if (resultFile.isOIFits2()) {
                // Process OI_CORR tables
                processOICorrs(ctx);

                // TODO: OIINSPOL ?
            }

            // 4. process Data:
            processOIData(ctx);
        }
        return resultFile;
    }

    private static OIFitsFile createOIFits(final OIFitsStandard std, final SelectorResult result) {
        OIFitsStandard version = std;

        if (version == null) {
            if (result == null) {
                version = OIFitsStandard.VERSION_1;
            } else {
                // Use the highest version from the OIFitsFile instances corresponding to the result:
                for (OIFitsFile oiFits : result.getSortedOIFitsFiles()) {
                    if (version == null || oiFits.getVersion().ordinal() > version.ordinal()) {
                        version = oiFits.getVersion();

                        if (version == OIFitsStandard.VERSION_2) {
                            // max(version) selected:
                            break;
                        }
                    }
                }
            }
        }

        logger.log(Level.INFO, "Using OIFITS {0}", version);

        return new OIFitsFile(version);
    }

    /**
     * Filter OiData tables
     *
     * @param oiFitsCollection OIFits collection
     * @param selector optional Selector instance to filter content
     * @return Selector result or null if no data match
     */
    private static SelectorResult filterData(final OIFitsCollection oiFitsCollection, final Selector selector) {
        logger.log(Level.INFO, "Selector: {0}", selector);

        // Query OIData matching criteria:
        final SelectorResult result = oiFitsCollection.findOIData(selector);

        logger.log(Level.FINE, "selectorResult: {0}", result);

        if (result == null) {
            logger.log(Level.INFO, "Merge: no matching data");
            return null;
        }

        logger.log(Level.INFO, "selected targets:  {0}", result.getDistinctTargets());
        logger.log(Level.INFO, "selected insModes: {0}", result.getDistinctInstrumentModes());
        logger.log(Level.INFO, "selected nightIds: {0}", result.getDistinctNightIds());

        return result;
    }

    /**
     * Collect all OIWavelength, OIArray and OICorr tables from OiData tables
     *
     * @param ctx merge context
     */
    private static void collectTables(final Context ctx) {
        // Collect all OIWavelength, OIArray and OICorr tables:
        for (OIData oiData : ctx.selectorResult.getSortedOIDatas()) {
            // Collect Primary header:
            final OIPrimaryHDU primaryHDU = oiData.getOIFitsFile().getOIPrimaryHDU();
            if (primaryHDU != null) {
                ctx.usedOIPrimaryHDU.add(primaryHDU);
            }
            // Collect referenced tables:
            final OITarget oiTarget = oiData.getOiTarget();
            if (oiTarget != null) {
                ctx.usedOITargets.add(oiTarget);
            }
            final OIWavelength oiWavelength = oiData.getOiWavelength();
            if (oiWavelength != null) {
                ctx.usedOIWavelengths.add(oiWavelength);
            }
            final OIArray oiArray = oiData.getOiArray();
            if (oiArray != null) {
                ctx.usedOIArrays.add(oiArray);
            }
            final OICorr oiCorr = oiData.getOiCorr();
            if (oiCorr != null) {
                ctx.usedOICorrs.add(oiCorr);
            }
            // TODO: OIInspol ?
        }
    }

    /**
     * Process Primary header
     *
     * @param ctx merge context
     * @throws IllegalArgumentException
     */
    private static void processOIPrimaryHDU(final Context ctx) throws IllegalArgumentException {
        final FitsImageHDU imageHdu;

        if (ctx.resultFile.getVersion() == OIFitsStandard.VERSION_2) {
            final OIPrimaryHDU primaryHdu;

            if (ctx.usedOIPrimaryHDU.size() == 1) {
                // single file => keep this primary HDU:
                primaryHdu = ctx.usedOIPrimaryHDU.iterator().next();
            } else {
                primaryHdu = new OIPrimaryHDU();

                // note: ignore header cards (extra keywords)
                final Map<String, Set<String>> keyValues = new HashMap<String, Set<String>>(32);

                // Collect distinct values for mandatory keywords:
                for (OIPrimaryHDU hdu : ctx.usedOIPrimaryHDU) {
                    if (logger.isLoggable(Level.FINE)) {
                        logger.log(Level.FINE, "Dump HDU: {0}", hdu.idToString());
                    }

                    for (KeywordMeta keyword : hdu.getKeywordDescCollection()) {
                        final String keywordName = keyword.getName();

                        if (!keyword.isOptional()) {
                            // get keyword value :
                            final Object keywordValue = hdu.getKeywordValue(keywordName);

                            if (keywordValue == null) {
                                // skip missing values
                                continue;
                            }

                            if (logger.isLoggable(Level.FINE)) {
                                logger.log(Level.FINE, "Get {0} = {1}", new Object[]{keywordName, keywordValue});
                            }

                            Set<String> values = keyValues.get(keywordName);
                            if (values == null) {
                                values = new LinkedHashSet<String>();
                                keyValues.put(keywordName, values);
                            }
                            values.add(keywordValue.toString());
                        }
                    }
                }
                // fill OIFITS2 Mandatory keywords:
                if (logger.isLoggable(Level.FINE)) {
                    logger.log(Level.FINE, "keyValues: {0}", keyValues);
                }

                // note: not really correct as filters can reduce the number of valid entries (TARGET ...)
                for (KeywordMeta keyword : primaryHdu.getKeywordDescCollection()) {
                    final String keywordName = keyword.getName();

                    if (!keyword.isOptional()) {
                        final Set<String> values = keyValues.get(keywordName);

                        final String keywordValue;
                        if (values == null) {
                            keywordValue = UNDEFINED;
                        } else {
                            if (values.size() == 1) {
                                keywordValue = values.iterator().next();
                            } else {
                                keywordValue = OIPrimaryHDU.VALUE_MULTI;
                            }
                        }
                        if (logger.isLoggable(Level.FINE)) {
                            logger.log(Level.FINE, "Set {0} = {1}", new Object[]{keywordName, keywordValue});
                        }
                        primaryHdu.setKeyword(keywordName, keywordValue);
                    }
                }
            }

            if (ctx.resultFile.getVersion() == OIFitsStandard.VERSION_2) {
                primaryHdu.setContent(FitsConstants.KEYWORD_CONTENT_OIFITS2);
            }

            // set date:
            primaryHdu.setDate(FitsDate.getFitsDateString());

            imageHdu = primaryHdu;
        } else {
            imageHdu = new FitsImageHDU();

            // set date:
            imageHdu.addHeaderCard(FitsConstants.KEYWORD_DATE, FitsDate.getFitsDateString(), "Date the HDU was written");
        }

        // Update history:
        imageHdu.addHeaderHistory("Written by JMMC OITools");

        ctx.resultFile.setPrimaryImageHdu(imageHdu);
    }

    /**
     * Process OITarget table
     *
     * @param ctx merge context
     * @throws IllegalArgumentException
     */
    private static void processOITarget(final Context ctx) throws IllegalArgumentException {
        final OIFitsFile resultFile = ctx.resultFile;

        // Targets:
        final TargetManager tm = ctx.selectorResult.getOiFitsCollection().getTargetManager();
        // keep targets associated to selected Granules ONLY:
        final List<Target> gTargets = ctx.selectorResult.getDistinctTargets();
        final int nbTargets = gTargets.size();

        final Map<Target, Short> newTargetIds = new IdentityHashMap<Target, Short>();

        final OITarget newOiTarget = new OITarget(resultFile, nbTargets);

        int i = 0;
        for (Target target : gTargets) {
            final Short targetId = Short.valueOf((short) (i + 1));
            newOiTarget.setTarget(i++, targetId, target);
            newTargetIds.put(target, targetId);
        }

        resultFile.addOiTable(newOiTarget);

        final Map<OITarget, Map<Short, Short>> mapOITargetIDs = ctx.mapOITargetIDs;

        // Define mapping per OITarget:
        for (OITarget oiTarget : ctx.usedOITargets) {
            final Map<Short, Short> mapIds = new HashMap<Short, Short>(4);

            for (Target target : gTargets) {
                final Set<Short> targetIds = oiTarget.getTargetIds(tm, target);

                if (targetIds != null) {
                    final Short newId = newTargetIds.get(target);

                    for (Short id : targetIds) {
                        mapIds.put(id, newId);
                    }
                }
            }
            mapOITargetIDs.put(oiTarget, mapIds);
        }

        logger.log(Level.FINE, "newTargetIds:   {0}", newTargetIds);
        logger.log(Level.FINE, "mapOITargetIDs: {0}", mapOITargetIDs);
    }

    /**
     * Process OIWavelength tables
     *
     * @param ctx merge context
     */
    private static void processOIWavelengths(final Context ctx) {
        // Browse all used tables, change name if already present in result, add to map [old table <=> new table]
        if (!ctx.usedOIWavelengths.isEmpty()) {
            final OIFitsFile resultFile = ctx.resultFile;
            final Map<OIWavelength, OIWavelength> mapOIWavelengths = ctx.mapOIWavelengths;
            final Map<OIWavelength, BitSet> maskOIWavelengths = ctx.maskOIWavelengths;

            final Selector selector = ctx.selectorResult.getSelector();

            // MJD ranges criteria:
            final List<Range> gWlRanges;
            final Set<Range> wlRangeMatchings;

            if ((selector != null) && selector.hasWavelengthRanges()) {
                gWlRanges = selector.getWavelengthRanges();
                wlRangeMatchings = new HashSet<Range>();
            } else {
                gWlRanges = null;
                wlRangeMatchings = null;
            }

            // TODO: use Instrument modes to reduce the output OIWavelength tables ? ie fuzzy comparison
            // Browse all used OIWavelength tables:
            for (OIWavelength oiWavelength : ctx.usedOIWavelengths) {
                final String name = oiWavelength.getInsName();

                // If name is already present in result, 
                // change the name and memorise this change to update data information later
                String newName = name;
                int idx = 0;
                OIWavelength prevOiWavelength = null;

                for (;;) {
                    prevOiWavelength = resultFile.getOiWavelength(newName);

                    if (prevOiWavelength == null) {
                        // table is not present
                        break;
                    } else {
                        // check if the existing table in resultFile is exactly the same ? (to remove duplicates)
                        if (OITableComparator.STRICT_COMPARATOR.compareTable(oiWavelength, prevOiWavelength)) {
                            logger.log(Level.INFO, "Same tables: {0} vs {1}", new Object[]{oiWavelength, prevOiWavelength});
                            // table is the same
                            break;
                        }
                    }
                    // use another suffix (_nn):
                    idx++;
                    newName = name + "_" + idx;
                }

                final OIWavelength newOiWavelength;
                BitSet maskRows = null;

                if (prevOiWavelength != null) {
                    // use the previous table
                    newOiWavelength = prevOiWavelength;
                } else {
                    // add the new table (copy):
                    newOiWavelength = (OIWavelength) resultFile.copyTable(oiWavelength);
                    newOiWavelength.setInsName(newName);

                    // check wavelength ranges:
                    boolean checkWlRanges = false;
                    if ((gWlRanges != null) && (wlRangeMatchings != null)) {
                        final Range wavelengthRange = oiWavelength.getInstrumentMode().getWavelengthRange();

                        logger.log(Level.FINE, "wavelength ranges: {0}", wavelengthRange);

                        // get matching wavelength ranges:
                        Range.getMatchingSelected(gWlRanges, wavelengthRange, wlRangeMatchings);

                        if (wlRangeMatchings.isEmpty()) {
                            logger.log(Level.FINE, "Skip {0}, no matching wavelength range", oiWavelength);
                            continue;
                        }
                        logger.log(Level.FINE, "matching wavelength ranges: {0}", wlRangeMatchings);

                        checkWlRanges = !Range.matchFully(wlRangeMatchings, wavelengthRange);
                    }

                    boolean filterRows = false;

                    if (checkWlRanges) {
                        final int nRows = newOiWavelength.getNbRows();

                        // prepare mask to indicate rows to keep in output table:
                        maskRows = new BitSet(nRows); // bits set to false by default

                        final float[] effWaves = (checkWlRanges) ? newOiWavelength.getEffWave() : null;

                        // Iterate on table rows (i):
                        for (int i = 0; i < nRows; i++) {
                            boolean skip = false;

                            if (checkWlRanges) {
                                if ((wlRangeMatchings != null) && !Range.contains(wlRangeMatchings, effWaves[i])) {
                                    // data row does not correspond to selected wavelength ranges 
                                    skip = true;
                                }
                            }

                            // update mask:
                            if (skip) {
                                filterRows = true;
                            } else {
                                maskRows.set(i);
                            }
                        }
                        if (filterRows) {
                            final int nKeepRows = maskRows.cardinality();

                            if (nKeepRows <= 0) {
                                // skip table as no remaining row
                                continue;
                            } else if (nKeepRows == nRows) {
                                // skip filter later in OIData:
                                maskRows = null;
                            } else {
                                // redim the table to the correct row count to prune invalid rows:
                                newOiWavelength.resizeTable(nKeepRows, maskRows);
                            }
                        }
                    }
                    resultFile.addOiTable(newOiWavelength);

                    if (filterRows) {
                        logger.log(Level.WARNING, "Table[{0}] filtered from Table[{1}]",
                                new Object[]{newOiWavelength, oiWavelength});
                    }
                }
                mapOIWavelengths.put(oiWavelength, newOiWavelength);
                maskOIWavelengths.put(oiWavelength, maskRows);
            }
            logger.log(Level.INFO, "insNames: {0}", Arrays.toString(resultFile.getAcceptedInsNames()));
            logger.log(Level.FINE, "mapOIWavelengths: {0}", mapOIWavelengths);
        }
    }

    /**
     * Process OIArray tables
     *
     * @param ctx merge context
     */
    private static void processOIArrays(final Context ctx) {
        // Browse all used tables, change name if already present in result, add to map [old table <=> new table]
        if (!ctx.usedOIArrays.isEmpty()) {
            final OIFitsFile resultFile = ctx.resultFile;
            final Map<OIArray, OIArray> mapOIArrays = ctx.mapOIArrays;

            // Browse all used OIArray tables:
            for (OIArray oiArray : ctx.usedOIArrays) {
                final String name = oiArray.getArrName();

                // If name is already present in result, 
                // change the name and memorise this change to update data information later
                String newName = name;
                int idx = 0;
                OIArray prevOiArray = null;

                for (;;) {
                    prevOiArray = resultFile.getOiArray(newName);

                    if (prevOiArray == null) {
                        // table is not present
                        break;
                    } else {
                        // check if the existing table in resultFile is exactly the same ? (to remove duplicates)
                        if (OITableComparator.STRICT_COMPARATOR.compareTable(oiArray, prevOiArray)) {
                            logger.log(Level.INFO, "Same tables: {0} vs {1}", new Object[]{oiArray, prevOiArray});
                            // table is the same
                            break;
                        }
                        // TODO: try all other possibilities ie all OI_ARRAYs having the same same
                        // Use hash or checksum to avoid costly raw table comparison ?
                    }
                    // use another suffix (_nn):
                    idx++;
                    newName = name + "_" + idx;
                }

                final OIArray newOiArray;

                if (prevOiArray != null) {
                    // use the previous table
                    newOiArray = prevOiArray;
                } else {
                    // add the new table (copy):
                    newOiArray = (OIArray) resultFile.copyTable(oiArray);
                    newOiArray.setArrName(newName);
                    resultFile.addOiTable(newOiArray);
                }
                mapOIArrays.put(oiArray, newOiArray);
            }
            logger.log(Level.INFO, "arrNames: {0}", Arrays.toString(resultFile.getAcceptedArrNames()));
            logger.log(Level.FINE, "mapOIArrays: {0}", mapOIArrays);
        }
    }

    /**
     * Process OICorr tables
     *
     * @param ctx merge context
     */
    private static void processOICorrs(final Context ctx) {
        // Browse all used tables, change name if already present in result, add to map [old table <=> new table]
        if (!ctx.usedOICorrs.isEmpty()) {
            final OIFitsFile resultFile = ctx.resultFile;
            final Map<OICorr, OICorr> mapOICorrs = ctx.mapOICorrs;

            // Browse all used OICorr tables:
            for (OICorr oiCorr : ctx.usedOICorrs) {
                final String name = oiCorr.getCorrName();

                // If name is already present in result, 
                // change the name and memorise this change to update data information later
                String newName = name;
                int idx = 0;
                while (resultFile.getOiCorr(newName) != null) {
                    idx++;
                    newName = name + "_" + idx;
                }

                final OICorr newOiCorr = (OICorr) resultFile.copyTable(oiCorr);
                newOiCorr.setCorrName(newName);
                resultFile.addOiTable(newOiCorr);

                mapOICorrs.put(oiCorr, newOiCorr);
            }
            logger.log(Level.INFO, "corrNames:  {0}", Arrays.toString(resultFile.getAcceptedCorrNames()));
            logger.log(Level.FINE, "mapOICorrs: {0}", mapOICorrs);
        }
    }

    /**
     *
     * @param ctx merge context
     */
    private static void processOIData(final Context ctx) {
        final SelectorResult selectorResult = ctx.selectorResult;
        final List<OIData> oiDatas = selectorResult.getSortedOIDatas();

        if (!oiDatas.isEmpty()) {
            logger.log(Level.INFO, "oiDatas: {0}", oiDatas);

            final OIFitsFile resultFile = ctx.resultFile;

            // keep nightIds associated to selected Granules ONLY:
            final List<NightId> gNightIds = selectorResult.getDistinctNightIds();
            // prepare NightIds matcher (generic):
            final NightIdMatcher nightIdMatcher = new NightIdMatcher(gNightIds);

            final Selector selector = selectorResult.getSelector();

            // MJD ranges criteria:
            final List<Range> gMJDRanges;
            final Set<Range> mjdRangeMatchings;

            if ((selector != null) && selector.hasMJDRanges()) {
                gMJDRanges = selector.getMJDRanges();
                mjdRangeMatchings = new HashSet<Range>();
            } else {
                gMJDRanges = null;
                mjdRangeMatchings = null;
            }

            // Baselines criteria:
            final List<String> gBaselines;
            final Map<String, StaNamesDir> usedStaNamesMap;
            final Set<short[]> staIndexMatchings; // identity

            if ((selector != null) && selector.hasBaselines()) {
                gBaselines = selectorResult.getSelector().getBaselines();
                usedStaNamesMap = selectorResult.getOiFitsCollection().getUsedStaNamesMap();
                staIndexMatchings = new HashSet<short[]>();
            } else {
                gBaselines = null;
                usedStaNamesMap = null;
                staIndexMatchings = null;
            }

            final Map<OIWavelength, OIWavelength> mapOIWavelengths = ctx.mapOIWavelengths;
            final Map<OIWavelength, BitSet> maskOIWavelengths = ctx.maskOIWavelengths;
            final Map<OIArray, OIArray> mapOIArrays = ctx.mapOIArrays;
            final Map<OICorr, OICorr> mapOICorrs = ctx.mapOICorrs;

            final Map<OITarget, Map<Short, Short>> mapOITargetIDs = ctx.mapOITargetIDs;

            for (OIData oiData : oiDatas) {
                final String newInsName;
                final String newArrName;
                final String newCorrName;

                // INSNAME:
                final OIWavelength newOiWavelength = mapOIWavelengths.get(oiData.getOiWavelength());
                if (newOiWavelength == null) {
                    logger.log(Level.WARNING, "Invalid INSNAME[{0}] found !", oiData.getInsName());
                    continue;
                }
                newInsName = newOiWavelength.getInsName();

                final BitSet maskWavelengths = maskOIWavelengths.get(oiData.getOiWavelength());
                logger.log(Level.FINE, "Mask WL: {0}", maskWavelengths);

                // check wavelengths:
                boolean checkWavelengths = (maskWavelengths != null);

                // ARRNAME:
                final OIArray newOiArray = mapOIArrays.get(oiData.getOiArray());
                if (newOiArray == null) {
                    newArrName = UNDEFINED;
                    logger.log(Level.WARNING, "Invalid ARRNAME[{0}] found ! Using [{1}] instead",
                            new Object[]{oiData.getArrName(), newArrName});
                } else {
                    newArrName = newOiArray.getArrName();
                }

                // Optional CORRNAME:
                if (oiData.getCorrName() == null) {
                    newCorrName = null;
                } else {
                    final OICorr newOiCorr = mapOICorrs.get(oiData.getOiCorr());
                    if (newOiCorr == null) {
                        newCorrName = null;
                        logger.log(Level.WARNING, "Invalid CORRNAME[{0}] found !", oiData.getCorrName());
                    } else {
                        newCorrName = newOiCorr.getCorrName();
                    }
                }

                // check Targets:
                boolean checkTargetId = false;
                // Should filter targetId on each data row ?
                final Map<Short, Short> mapTargetIds = mapOITargetIDs.get(oiData.getOiTarget());

                for (Short id : oiData.getDistinctTargetId()) {
                    final Short newId = mapTargetIds.get(id);
                    if (newId == null) {
                        checkTargetId = true;
                        // mark this id as UNDEFINED to be filtered out:
                        mapTargetIds.put(id, UNDEFINED_SHORT);

                        logger.log(Level.INFO, "Filter TargetId = {0}.", id);
                    } else {
                        // targetId value are different between input table and output:
                        if (!id.equals(newId)) {
                            checkTargetId = true;
                        }
                    }
                }

                logger.log(Level.FINE, "checkTargetId: {0}", checkTargetId);
                logger.log(Level.FINE, "mapIds:        {0}", mapTargetIds);

                // check nightIds:
                boolean checkNightId = false;
                // Should filter nightId on each data row ?
                if (!oiData.hasSingleNight()) {
                    if (!nightIdMatcher.matchAll(oiData.getDistinctNightId())) {
                        checkNightId = true;
                    }
                    logger.log(Level.FINE, "oidata nightIds: {0}", oiData.getDistinctNightId());
                }

                // check baselines:
                boolean checkBaselines = false;
                if ((gBaselines != null) && (staIndexMatchings != null)) {
                    if (logger.isLoggable(Level.FINE)) {
                        logger.log(Level.FINE, "oiData distinct StaIndexes: {0}", oiData.getDistinctStaIndex());
                    }
                    // collect matching baselines (as usual staIndex instances):
                    oiData.getMatchingStaIndexes(usedStaNamesMap, gBaselines, staIndexMatchings);

                    if (staIndexMatchings.isEmpty()) {
                        logger.log(Level.FINE, "Skip {0}, no matching baseline", oiData);
                        continue;
                    }
                    logger.log(Level.FINE, "staIndexMatching: {0}", staIndexMatchings);

                    if (oiData.getDistinctStaIndex().size() > staIndexMatchings.size()) {
                        checkBaselines = true;
                    }
                }

                // check MJD ranges:
                boolean checkMJDRanges = false;
                if ((gMJDRanges != null) && (mjdRangeMatchings != null)) {
                    final Set<Range> oiDataMJDRanges = oiData.getDistinctMJDRanges().keySet();

                    logger.log(Level.FINE, "oiData distinct MJD ranges: {0}", oiDataMJDRanges);

                    // get matching wavelength ranges:
                    Range.getMatchingSelected(gMJDRanges, oiDataMJDRanges, mjdRangeMatchings);

                    if (mjdRangeMatchings.isEmpty()) {
                        logger.log(Level.FINE, "Skip {0}, no matching MJD range", oiData);
                        continue;
                    }
                    logger.log(Level.FINE, "matching MJD ranges: {0}", mjdRangeMatchings);

                    checkMJDRanges = !Range.matchFully(oiDataMJDRanges, mjdRangeMatchings);
                }

                final OIData newOIData = (OIData) resultFile.copyTable(oiData);

                // Change INSNAME, ARRNAME & CORRNAME keywords:
                newOIData.setArrName(newArrName);
                newOIData.setInsName(newInsName);
                newOIData.setCorrName(newCorrName);

                boolean filterRows = false;

                final BitSet maskRows;
                if (checkTargetId || checkNightId || checkBaselines || checkMJDRanges || checkWavelengths) {
                    final int nRows = newOIData.getNbRows();

                    // prepare mask to indicate rows to keep in output table:
                    maskRows = new BitSet(nRows); // bits set to false by default

                    // Update targetId column:
                    final short[] targetIds = newOIData.getTargetId();
                    final short[] newTargetIds = new short[nRows];

                    final int[] nightIds = (checkNightId) ? newOIData.getNightId() : null;
                    final double[] mjds = (checkMJDRanges) ? newOIData.getMJD() : null;
                    final short[][] staIndexes = (checkBaselines) ? newOIData.getStaIndex() : null;

                    // Iterate on table rows (i):
                    for (int i = 0; i < nRows; i++) {
                        boolean skip = false;

                        if (checkTargetId) {
                            final Short oldTargetId = Short.valueOf(targetIds[i]);
                            Short newTargetId = mapTargetIds.get(oldTargetId);
                            if (newTargetId == null) {
                                newTargetId = UNDEFINED_SHORT; // should never happen
                            }
                            newTargetIds[i] = newTargetId.shortValue();
                        } else {
                            // preserve id:
                            newTargetIds[i] = targetIds[i];
                        }
                        if (newTargetIds[i] == ModelBase.UNDEFINED_SHORT) {
                            skip = true;
                        }

                        if (checkNightId && !skip) {
                            if (!nightIdMatcher.match(nightIds[i])) {
                                // data row does not correspond to current night
                                skip = true;
                            }
                        }
                        if (checkMJDRanges && !skip) {
// wrong range: see wl ranges !                            
                            if ((mjdRangeMatchings != null) && !Range.contains(mjdRangeMatchings, mjds[i])) {
                                // data row does not correspond to selected MJD ranges 
                                skip = true;
                            }
                        }
                        if (checkBaselines && !skip) {
                            if ((staIndexMatchings != null) && !staIndexMatchings.contains(staIndexes[i])) {
                                // data row does not correspond to selected baselines
                                skip = true;
                            }
                        }

                        // update mask:
                        if (skip) {
                            filterRows = true;
                        } else {
                            maskRows.set(i);
                        }
                    }

                    // update targetId column before table filter:
                    newOIData.setTargetId(newTargetIds);

                    if (filterRows || checkWavelengths) {
                        final int nKeepRows = maskRows.cardinality();

                        if (nKeepRows <= 0) {
                            // skip table as no remaining row
                            continue;
                        } else {
                            // redim the table to the correct row count to prune invalid rows:
                            newOIData.resizeTable(nKeepRows, maskRows, maskWavelengths);
                        }
                    }
                }
                resultFile.addOiTable(newOIData);

                if (filterRows) {
                    logger.log(Level.WARNING, "Table[{0}] filtered from Table[{1}]",
                            new Object[]{newOIData, oiData});
                }
            }
        }
    }

    /**
     * Hold temporary data of merge operation
     */
    static final class Context {

        /** Selector result */
        final SelectorResult selectorResult;
        /** output OIFits */
        final OIFitsFile resultFile;
        /* Set of OIPrimaryHDU, OITarget, OIWavelength, OIArray and OICorr tables to process */
        final Set<OIPrimaryHDU> usedOIPrimaryHDU = new LinkedHashSet<OIPrimaryHDU>();
        final Set<OITarget> usedOITargets = new LinkedHashSet<OITarget>();
        final Set<OIWavelength> usedOIWavelengths = new LinkedHashSet<OIWavelength>();
        final Set<OIArray> usedOIArrays = new LinkedHashSet<OIArray>();
        final Set<OICorr> usedOICorrs = new LinkedHashSet<OICorr>();
        /** Map per OITarget between old targetIds (local) to new targetId (global) */
        final Map<OITarget, Map<Short, Short>> mapOITargetIDs = new IdentityHashMap<OITarget, Map<Short, Short>>();
        /* Map between old table to new tables for OIWavelength, OIArray and OICorr tables */
        final Map<OIWavelength, OIWavelength> mapOIWavelengths = new IdentityHashMap<OIWavelength, OIWavelength>();
        final Map<OIArray, OIArray> mapOIArrays = new IdentityHashMap<OIArray, OIArray>();
        final Map<OICorr, OICorr> mapOICorrs = new IdentityHashMap<OICorr, OICorr>();
        /** Map between old table to BitSet (mask) for OIWavelength */
        final Map<OIWavelength, BitSet> maskOIWavelengths = new IdentityHashMap<OIWavelength, BitSet>();

        private Context(final SelectorResult selectorResult, final OIFitsFile resultFile) {
            this.selectorResult = selectorResult;
            this.resultFile = resultFile;
        }
    }

}
