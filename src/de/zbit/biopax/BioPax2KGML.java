/*
 * $Id$
 * $URL$
 * ---------------------------------------------------------------------
 * This file is part of SBVC, the systems biology visualizer and
 * converter. This tools is able to read a plethora of systems biology
 * file formats and convert them to an internal data structure.
 * These files can then be visualized, either using a simple graph
 * (KEGG-style) or using the SBGN-PD layout and rendering constraints.
 * Some currently supported IO formats are SBML (+qual, +layout), KGML,
 * BioPax, SBGN, etc. Please visit the project homepage at
 * <http://www.cogsys.cs.uni-tuebingen.de/software/SBVC> to obtain the
 * latest version of SBVC.
 *
 * Copyright (C) 2012 by the University of Tuebingen, Germany.
 *
 * SBVC is free software; you can redistribute it and/or 
 * modify it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation. A copy of the license
 * agreement is provided in the file named "LICENSE.txt" included with
 * this software distribution and also available online as
 * <http://www.gnu.org/licenses/lgpl-3.0-standalone.html>.
 * ---------------------------------------------------------------------
 */
package de.zbit.biopax;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.biopax.paxtools.io.BioPAXIOHandler;
import org.biopax.paxtools.io.SimpleIOHandler;
import org.biopax.paxtools.model.BioPAXElement;
import org.biopax.paxtools.model.BioPAXLevel;
import org.biopax.paxtools.model.Model;
import org.biopax.paxtools.model.level2.dataSource;
import org.biopax.paxtools.model.level2.pathway;
import org.biopax.paxtools.model.level3.Pathway;
import org.biopax.paxtools.model.level3.Provenance;
import org.biopax.paxtools.util.IllegalBioPAXArgumentException;

import de.zbit.io.FileTools;
import de.zbit.kegg.KGMLWriter;
import de.zbit.kegg.api.KeggInfos;
import de.zbit.mapper.GeneID2KeggIDMapper;
import de.zbit.mapper.GeneSymbol2GeneIDMapper;
import de.zbit.util.ArrayUtils;
import de.zbit.util.DatabaseIdentifiers.IdentifierDatabases;
import de.zbit.util.Species;
import de.zbit.util.Utils;
import de.zbit.util.objectwrapper.ValuePair;

/**
 * This class is a base class to convert BioPax files and contains all methods
 * which are use for LEVEL 2 and LEVEL 3 converting
 * 
 * @author Finja B&uuml;chel
 * @version $Rev$
 */
public abstract class BioPax2KGML {

  public static final Logger log = Logger.getLogger(BioPax2KGML.class.getName());

  /**
   * default folder name for the KGMLs "pws"
   */
  static String defaultFolderName = "pws";
  static List<Species> allSpecies = null;

  static {
    // TODO: Replace this with a flat-text file in resources to support more
    // organisms.
    allSpecies = new ArrayList<Species>(4);
    allSpecies.add(new Species("Homo sapiens", "_HUMAN", "Human", "hsa", 9606));
    allSpecies.add(new Species("Mus musculus", "_MOUSE", "Mouse", "mmu", 10090));
    allSpecies.add(new Species("Rattus norvegicus", "_RAT", "Rat", "rno", 10116));
    allSpecies.add(new Species("Enterococcus faecalis", "_ENTFA", "Enterococcus", "efa", 226185));
  }

  /**
   * This variable must be set to false for normal biopax2kgml conversion.
   * 
   * If it is true, an existing pathway will be augmented with relations, and
   * relation subtypes. NO reactions and NO entries are added!!!
   */
  boolean augmentOriginalKEGGpathway = false;
  boolean addSelfReactions = false;
  int newAddedRelations = 0;
  int selfRelation = 0;
  int addedSubTypes = 0;

  /**
   * number which is used to determine a pathway id, if it is not possible to
   * exclude the id from the BioCarta file
   */
  int keggPathwayNumberCounter = 100000;

  /**
   * this variable is used to determine the kegg id of an entry
   */
  int keggEntryID = 0;

  /**
   * this variable is used to determine the kegg id of an entry
   */
  static int keggUnknownNo = 0;

  /**
   * this variable is used to determine the kegg reaction id
   */
  int keggReactionID = 0;

  /**
   * default organism for KEGG parsing - "hsa"
   */
  String organism = "hsa";

  /**
   * undefined, if we have no gene id to set the kegg name of an entry we use
   * this name
   */
  public static final String keggUnknownName = "unknown";

  /**
   * mapper to map gene symbols to gene ids
   */
  protected static GeneSymbol2GeneIDMapper geneSymbolMapper = null;

  /**
   * mapper to map gene ids to KEGG ids
   */
  protected static GeneID2KeggIDMapper geneIDKEGGmapper = null;

  /**
   * transforms a set to a map. The key is a RDFId and the value the
   * corresponding object
   * 
   * @param <T>
   * @param set
   *          to convert
   * @return the converted map
   */
  protected static <T extends BioPAXElement> Map<String, T> getMapFromSet(Set<T> set) {
    Map<String, T> map = new HashMap<String, T>();
    for (T elem : set) {
      map.put(elem.getRDFId(), elem);
    }

    return map;
  }

  /**
   * 
   * @return the new KEGG unknown "unknownx", whereas x is set to the
   *         {@link BioPax2KGML#keggUnknownNo}.{@link BioPax2KGML#keggUnknownNo}
   *         is incremented after this step
   */
  protected static String getKEGGUnkownName() {
    return keggUnknownName + String.valueOf(++keggUnknownNo);
  }

  /**
   * 
   * @return the new KEGG reaction name "rn:unknownx", whereas x is set to the
   *         {@link BioPax2KGML#keggReactionID}.
   *         {@link BioPax2KGML#keggReactionID} is augmented after this step
   */
  protected String getReactionName() {
    return "rn:unknown" + String.valueOf(++keggReactionID);
  }

  /**
   * mapps an entered gene id to a kegg id, if this is not possible the species
   * abbreviation:geneID is returned
   * 
   * @param mapper
   * @return
   */
  protected static String mapGeneIDToKEGGID(Integer geneID, Species species) {
    String keggName = null;
    try {
      keggName = geneIDKEGGmapper.map(geneID);
    } catch (Exception e) {
      log.log(Level.WARNING, "Could not map geneid: '" + geneID.toString() + "' to a KEGG id, "
          + "'speciesAbbreviation:geneID will be used instead.", e);
    }

    if (keggName == null) {
      keggName = species.getKeggAbbr() + ":" + geneID.toString();
    }

    return keggName;
  }

  /**
   * The rdfID is in the format: http://pid.nci.nih.gov/biopaxpid_9717
   * 
   * From this id the number is excluded and used as pathway number, if this is
   * not possible the {@link BioPaxL22KGML#keggPathwayNumberCounter} is used and
   * incremented
   * 
   * @param rdfId
   * @return
   */
  protected int getKeggPathwayNumber(String rdfId) {
    int posUnderscore = rdfId.indexOf('_');
    if (posUnderscore > -1 && posUnderscore <= rdfId.length()) {
      try {
        return Integer.parseInt(rdfId.substring(posUnderscore + 1));
      } catch (Exception e) {
        return keggPathwayNumberCounter++;
      }
    }

    return keggPathwayNumberCounter++;
  }

  /**
   * Converts the inputStream of an owl file containing BioPAX entries
   * 
   * @param io
   * @return
   */
  public static Model getModel(InputStream io) {
    BioPAXIOHandler handler = new SimpleIOHandler();
    Model m = null;
    try {
      m = handler.convertFromOWL(io);
    } catch (IllegalBioPAXArgumentException e) {
      log.log(Level.SEVERE, "Could not read model!", e);
    }
    return m;
  }

  /**
   * The {@link BioPax2KGML}{@link #geneSymbolMapper} and
   * {@link BioPax2KGML#geneIDKEGGmapper} are initialized for the entered
   * species
   * 
   * @param species
   */
  public static void initalizeMappers(Species species) {
    try {
      geneSymbolMapper = new GeneSymbol2GeneIDMapper(species.getCommonName());
    } catch (IOException e) {
      log.log(Level.SEVERE, "Could not initalize mapper for species '" + species.toString() + "'!",
          e);
      System.exit(1);
    }

    try {
      geneIDKEGGmapper = new GeneID2KeggIDMapper(species);
    } catch (IOException e) {
      log.log(Level.SEVERE, "Error while initializing gene id to KEGG ID mapper for species '"
          + species.toString() + "'.", e);
      System.exit(1);
    }
  }

  /**
   * @return a unique {@link BioPaxL22KGML#keggEntryID}.
   */
  protected int getKeggEntryID() {
    keggEntryID++;
    return keggEntryID;
  }

  /**
   * determines the link for the pathway image
   * 
   * @param species
   * @param pathway
   * @param keggPW
   */
  public void addImageLinkToKEGGpathway(Species species, String pathwayName,
      de.zbit.kegg.parser.pathway.Pathway keggPW) {
    String linkName = pathwayName;
    if (!linkName.equals("") && linkName.contains("pathway")) {
      linkName = linkName.replace("pathway", "Pathway");

      if (species.getKeggAbbr().equals("hsa")) {
        keggPW.setLink("http://www.biocarta.com/pathfiles/h_" + linkName + ".asp");
        keggPW.setImage("http://www.biocarta.com/pathfiles/h_" + linkName + ".gif");
      } else if (species.getKeggAbbr().equals("mmu")) {
        keggPW.setLink("http://www.biocarta.com/pathfiles/m_" + linkName + ".asp");
        keggPW.setImage("http://www.biocarta.com/pathfiles/m_" + linkName + ".gif");
      }
    }
  }

  /**
   * Creates a folder depending on the {@link BioPax2KGML#defaultFolderName} and
   * the {@link BioPAXLevel}
   * 
   * @param level
   * @return the folderName
   */
  protected static String createDefaultFolder(BioPAXLevel level) {
    String folderName = defaultFolderName + level.toString() + "/";
    if (!new File(folderName).exists()) {
      boolean success = (new File(folderName)).mkdir();
      if (success) {
        log.log(Level.SEVERE, "Could not create directory '" + folderName + "'");
        System.exit(1);
      }
    }

    return folderName;
  }

  /**
   * Calls the method {@link BioPax2KGML#getModel(InputStream) for an entered
   * owl file}
   * 
   * @param file
   * @return Model
   */
  public static Model getModel(String file) {
    InputStream io = null;
    try {
      io = new FileInputStream(new File(file));
    } catch (FileNotFoundException e) {
      log.log(Level.SEVERE, "Could not parse file: " + file + ".", e);
    }

    log.log(Level.CONFIG, "Model sucessfully created");
    return getModel(io);
  }

  /**
   * Maps the entered gene symbol names to a geneID
   * 
   * @param set of gene symbols
   * @return the gene id (default value = null)
   */
  protected static Integer getEntrezGeneIDForGeneSymbol(Collection<String> geneSymbols) {
    log.finest("getGeneIDOverGeneSymbol");
    Integer geneID = null;

    for (String symbol : geneSymbols) {
      try {
        geneID = geneSymbolMapper.map(symbol);
      } catch (Exception e) {
        log.log(Level.WARNING, "Error while mapping name: " + symbol + ".", e);
      }

      if (geneID != null) {
        return geneID;
      } else if (symbol.contains("-")) {
        return getEntrezGeneIDForGeneSymbol(Collections.singleton(symbol.replace("-", "")));
      } else if (symbol.contains(" ")) {
        return getEntrezGeneIDForGeneSymbol(Collections.singleton(symbol.replace(" ", "_")));
      } else {
        log.log(Level.FINER, "----- not found " + symbol);
      }
    }
    return geneID;
  }
  
  /**
   * Creates for an entered {@link Model} the corresponding KEGG pathways
   * 
   * @param m
   */
  public static Collection<de.zbit.kegg.parser.pathway.Pathway> createPathwaysFromModel
    (String fileName, String destinationFolder,
      boolean singleMode, boolean writeEntryExtended) {
    Model m = BioPax2KGML.getModel(fileName);
    return createPathwaysFromModel(m, fileName, destinationFolder, singleMode, writeEntryExtended); 
  }
  
  /**
   * Creates for an entered {@link Model} the corresponding KEGG pathways
   * 
   * @param m
   */
  public static Collection<de.zbit.kegg.parser.pathway.Pathway> createPathwaysFromModel
    (Model m, String fileName, String destinationFolder,
      boolean singleMode, boolean writeEntryExtended) {
    Collection<de.zbit.kegg.parser.pathway.Pathway> keggPWs = 
      new ArrayList<de.zbit.kegg.parser.pathway.Pathway>(); 
       
    if (m!=null){
      File f = new File(fileName);
      if (destinationFolder == null || destinationFolder.isEmpty()) {
        destinationFolder = createDefaultFolder(m.getLevel());
      }

      String comment = getRDFScomment(f);
      
      
      // BioPax Level 2 
      if (m.getLevel().equals(BioPAXLevel.L2)) {
        BioPaxL22KGML bp = new BioPaxL22KGML();
        Set<pathway> pathways = m.getObjects(pathway.class);
        // if (pathways!=null && pathways.size()>0) {
        if (!singleMode) {
          keggPWs = bp.parsePathways(m, comment, pathways);
        } else {
          de.zbit.kegg.parser.pathway.Pathway keggPW = 
            bp.createPathwayFromBioPaxFile(m, comment, FileTools.removeFileExtension(f.getName()));
          keggPWs.add(keggPW);   
        }
      } //BioPax Level 3
        else if (m.getLevel().equals(BioPAXLevel.L3)) {
        BioPaxL32KGML bp = new BioPaxL32KGML();
        Set<Pathway> pathways = m.getObjects(Pathway.class);
        
        if (!singleMode) {
          keggPWs = bp.parsePathways(m, comment, pathways);
        } else {
          de.zbit.kegg.parser.pathway.Pathway keggPW = bp.createPathwayFromBioPaxFile
          (m, comment, FileTools.removeFileExtension(f.getName()));
          keggPWs.add(keggPW);          
        }
      } else {
        log.log(Level.SEVERE, "Unkown BioPax Level '" + m.getLevel().toString()
            + "' is not supported.");
        System.exit(1);
      }      
      
    } else {
      log.log(Level.SEVERE, "Could not continue, because the model is null.");
    }    
    
    return keggPWs;
  }
  
  /**
   * parses all pathways of the owl file and writes them in the KGML format
   * @param fileName
   * @param destinationFolder
   * @param singleMode
   * @param writeEntryExtended
   */
  public static void writeKGMLsForPathways(String fileName, String destinationFolder,
      boolean singleMode, boolean writeEntryExtended) {
    Model m = BioPax2KGML.getModel(fileName);
    Collection<de.zbit.kegg.parser.pathway.Pathway> keggPWs = 
      BioPax2KGML.createPathwaysFromModel(m, fileName, destinationFolder,
      singleMode, writeEntryExtended);
    BioPax2KGML.writeKGMLsForPathways(m, destinationFolder, keggPWs, writeEntryExtended);
  }
  
  
  /**
   * This method creates for each pathway in the model a KGML file with the
   * pathway name and saves the pathways in an default folder see 
   * {@link BioPax2KGML#createDefaultFolder(org.biopax.paxtools.model.BioPAXLevel)}
   * 
   * @param m
   */
  public static void writeKGMLsForPathways(Model m, Collection<de.zbit.kegg.parser.pathway.Pathway> pathways, 
      boolean writeEntryExtended) {
    String folder = createDefaultFolder(m.getLevel());
    writeKGMLsForPathways(m, folder, pathways, writeEntryExtended);
  }
  
  /**
   * This method creates for each pathway of the set a KGML file with the
   * pathway name
   * 
   * @param m
   */
  public static void writeKGMLsForPathways(Model m, String folder, 
      Collection<de.zbit.kegg.parser.pathway.Pathway> pathways, boolean writeEntryExtended) {
    log.info("Creating for each pathway a KGML file.");

    for (de.zbit.kegg.parser.pathway.Pathway keggPW : pathways) {
      String pFile = folder + KGMLWriter.createFileName(keggPW);
      KGMLWriter.writeKGML(keggPW, pFile, writeEntryExtended);
    }
  }
  

  /**
   * In this method the <rdfs:comment rdf:datatype="http://www.w3.org/2001/XMLSchema#string">
   * ...</rdfs:comment> is parse  
   * @return
   */
  public static String getRDFScomment(File f) {
    String comment = "", line = "";
    String pattern = ".*<rdfs:comment.*?\">.*?</rdfs:comment>.*";
    BufferedReader br;
    try {
      br = new BufferedReader(new FileReader(f));
      while ((line = br.readLine())!=null){
        if (line.matches(pattern))
          break;
      }
    } catch (FileNotFoundException e) {
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    }
    
    line = line.replaceFirst(".*<rdfs:comment.*?\">", "");
    line = line.replaceFirst("</rdfs:comment>.*", "");
    
    if (!line.isEmpty())
      return line;
    
    return comment;
  }

  /**
   * @param
   * @return name of the set without blanks
   */
  protected String getNameWithoutBlanks(Set<String> names) {
    String name = "";

    if (names != null && names.size() > 0) {
      List<String> names2 = Utils.iterableToList(names);
      for (int i = names2.size() - 1; i > 0; i--) {
        name = names2.get(i);
        if (name.length() > 0 && !name.contains(" "))
          return name;
      }
    }

    return name;
  }

  /**
   * 
   * @param identifiers
   * @param species 
   * @return
   */
  @SuppressWarnings("unchecked")
  public static String getKEGGName(Map<IdentifierDatabases, Collection<String>> identifiers, Species species) {
    Set<String> ids = new HashSet<String>();

    ArrayUtils.merge(ids,
        identifiers.get(IdentifierDatabases.KEGG_Compound),
        identifiers.get(IdentifierDatabases.KEGG_Drug),
        identifiers.get(IdentifierDatabases.KEGG_Genes),
        identifiers.get(IdentifierDatabases.KEGG_Glycan),
        identifiers.get(IdentifierDatabases.KEGG_Orthology));
    
    // Add mapped entrez genen id to set
    Collection<String> geneID = null;
    Collection<String> geneIDs = identifiers.get(IdentifierDatabases.EntrezGene);   
    if (geneIDs!=null && geneIDs.size()>0){
      geneID = geneIDs;  
    } else {
      // we have to search the gene id with the gene symbol, adding symbol to
      // the gene symbol set  
      Collection<String> geneSymbols = identifiers.get(IdentifierDatabases.GeneSymbol);
      if(geneSymbols!=null && geneSymbols.size()>0) {
        Integer geneID2 = getEntrezGeneIDForGeneSymbol(geneSymbols);
        if (geneID2!=null && geneID2>0) {
          geneID = Collections.singleton(geneID2.toString());
        }
      }
    }
       
    if (geneID!=null && geneID.size()>0) {
      for (String geneID2: geneID) {
        if (Utils.isNumber(geneID2, true)) {
          String keggid = mapGeneIDToKEGGID(Integer.parseInt(geneID2), species);
          ids.add(keggid);
        }
      }
    }
    
    // Append prefixes for KEGG IDs
    Set<String> idsFixed = new HashSet<String>();
    Iterator<String> it = ids.iterator();
    while (it.hasNext()) {
      String id = it.next();
      if (!id.contains(":")) {
        id = KeggInfos.appendPrefix(id);
      }
      idsFixed.add(id);
    }
    
    // Implode and set name
    String keggName = ArrayUtils.implode(idsFixed, " ");
    if (keggName==null) {
      keggName = getKEGGUnkownName();
    }
    
    return keggName;
  }

  public de.zbit.kegg.parser.pathway.Pathway createPathwayInstance(String comment, Object pathway,
      Species speciespathwayName) {
    return createPathwayInstance(comment, pathway, speciespathwayName, null);
  }
  public de.zbit.kegg.parser.pathway.Pathway createPathwayInstance(String comment, Object pathway,
      Species species, String pathwayName) {
    // create the pathway
    
    int number = pathway.hashCode();
    String sourceDB="";
    String pwName="unknown";
    String link = null;
    if (pathway instanceof BioPAXElement) {
      getKeggPathwayNumber(((BioPAXElement)pathway).getRDFId());
    }
    if (pathway instanceof pathway) { // Level2
      pathway p = (pathway) pathway;
      ValuePair<String, String> sAndl = getSourceDBL2(p.getDATA_SOURCE());
      sourceDB = sAndl.getA();
      link = sAndl.getB();
      pwName = p.getNAME();
    } else if (pathway instanceof Pathway) {
      Pathway p = (Pathway) pathway;
      ValuePair<String, String> sAndl = getSourceDBL3(p.getDataSource());
      sourceDB = sAndl.getA();
      link = sAndl.getB();
      pwName = BioPaxL32KGML.getPathwayName(p);
    }
    if (pathwayName!=null && pathwayName.length()>0) {
      pwName = pathwayName;
    }
    
    de.zbit.kegg.parser.pathway.Pathway keggPW = new de.zbit.kegg.parser.pathway.Pathway(
        sourceDB + String.valueOf(number), species.getKeggAbbr(), number, pwName);
    keggPW.setComment(comment);
    keggPW.setOriginFormatName("BioPax");
    
    if(link!=null) {
      keggPW.setLink(link);
    }
    
    
    
    return keggPW;
  }

  /**
   * sets the source of the data if available to the class
   * <p>For Level2
   * @param sources
   * @param keggPW
   */
  private static ValuePair<String, String> getSourceDBL2(Set<dataSource> sources) {
    String sourceDB = "";
    String link = "";
    if (sources!=null && sources.size()>0){
      for (dataSource source : sources) {
        if(source.getNAME()!=null && source.getNAME().size()>0){
          sourceDB = source.getNAME().iterator().next();
        }
        if(source.getCOMMENT()!=null && source.getCOMMENT().size()>0){
          link = source.getCOMMENT().iterator().next();
        }
      }
    }
    
    return new ValuePair<String, String>(sourceDB, link);
  }
  
  /**
   * sets the source of the data if available to the class
   * <p>For Level 3
   * @param sources
   * @param keggPW
   */
  private static ValuePair<String, String> getSourceDBL3(Set<Provenance> sources) {
    
    String source = "";
    String link = "";
    if (sources!=null && sources.size()>0){
      for (Provenance p : sources) {
        if(p.getName()!=null && p.getName().size()>0){
          source = p.getName().iterator().next();          
        }
        if(p.getComment()!=null && p.getComment().size()>0){
          link = p.getComment().iterator().next();          
        }
      }
    }
    
    return new ValuePair<String, String>(source, link);
  }
}
