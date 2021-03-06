/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package graphetry.database;

import com.sun.speech.freetts.lexicon.LetterToSoundImpl;
import graphetry.util.WordUtils;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.neo4j.cypher.javacompat.ExecutionEngine;
import org.neo4j.cypher.javacompat.ExecutionResult;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.neo4j.graphdb.index.Index;
import org.neo4j.graphdb.index.IndexHits;
import org.neo4j.helpers.collection.IteratorUtil;
import scala.actors.threadpool.Arrays;

/**
 *
 * @author Sam
 */
public class DatabaseControl {

    private GraphDatabaseService graphDb;
    private ExecutionEngine engine;
    private final int ORDER = 2;
    private final Index<Node> nodeIndex;
    private LetterToSoundImpl lts;
    private final static int RHYME_PHONES = 2; // Number of metaphone characters to store for rhyming.
    private final static int SENTENCE_LIMIT = 500;  // Maximum sentence length in words.

    private static enum BuildDirection {

        BOTH, FORWARD_ONLY, BACKWARD_ONLY
    };

    private static enum RelTypes implements RelationshipType {

        ZERO, ONE, TWO, THREE, FOUR, FIVE, SIX
    }
    public static String P_WORD = "wordkey";
    public static String P_SYLLABLES = "syllables";
    private static String P_END = "endnode";
    private static String I_WORD = "word";
    private static String I_END = "endnode";
    private static String I_PHON = "phoneme";
    private Node START_NODE;
    private Node END_NODE;

    public DatabaseControl(String dbPath) {
        this(dbPath, 2);
    }

    private DatabaseControl(String dbPath, int order) {
        System.out.println("Initializing database of order " + order + "...");
        //this.ORDER = order;
        graphDb = new GraphDatabaseFactory().newEmbeddedDatabase(dbPath);
        engine = new ExecutionEngine(graphDb);
        nodeIndex = graphDb.index().forNodes("node_idx");
        verifyEndNodeExistance();
        //keywordIndex = graphDb.index().forNodes("keyword");
        //userIndex = graphDb.index().forNodes("user");
        registerShutdownHook(graphDb);
        System.out.println("Database initialized.");


        try {
            System.out.println("Initializing g2p...");
            lts = new LetterToSoundImpl(new URL("jar:file:lib/freetts/cmudict04.jar!/com/sun/speech/freetts/en/us/cmudict04_lts.bin"), true);
            System.out.println("g2p initialized.");
        } catch (IOException ex) {
            Logger.getLogger(DatabaseControl.class.getName()).log(Level.SEVERE, null, ex);
        }

    }

    private void verifyEndNodeExistance() {
        Transaction tx = graphDb.beginTx();
        try {
            START_NODE = nodeIndex.get(I_END, "START").getSingle();
            if (START_NODE == null) {
                START_NODE = graphDb.createNode();
                START_NODE.setProperty(P_END, "START");
                nodeIndex.putIfAbsent(START_NODE, I_END, "START");
            }
            END_NODE = nodeIndex.get(I_END, "END").getSingle();
            if (END_NODE == null) {
                END_NODE = graphDb.createNode();
                END_NODE.setProperty(P_END, "END");
                nodeIndex.putIfAbsent(END_NODE, I_END, "END");
            }
            tx.success();
        } finally {
            tx.finish();
        }
    }

    /**
     * Writes an array of pre-processed, cleaned, words to the graph database.
     *
     * @param inputArray
     */
    public void writeArrayToGraph(String[] inputArray) {
        if (inputArray.length < ORDER + 1) {
            throw new IllegalArgumentException("Input array must be at least " + (ORDER + 1) + " items long (ORDER+1)");
        }

        Transaction tx = graphDb.beginTx();
        try {
            Node[] workingNodes = new Node[inputArray.length + 2];// Add two for end nodes.


            for (int i = 0; i < workingNodes.length; i++) {
                Node newNode;

                if (i == 0) { // Use start node.
                    newNode = START_NODE;
                } else if (i == workingNodes.length - 1) { // Use end node
                    newNode = END_NODE;
                } else {
                    // Find/Create new word node
                    Node foundNode = nodeIndex.get(I_WORD, inputArray[i - 1]).getSingle();
                    if (foundNode != null) {
                        newNode = foundNode;
                    } else {
                        newNode = graphDb.createNode();
                        newNode.setProperty(P_WORD, inputArray[i - 1]);
                        newNode.setProperty(P_SYLLABLES, WordUtils.countSyllables(inputArray[i - 1]));
                        nodeIndex.putIfAbsent(newNode, I_WORD, inputArray[i - 1]);
                        nodeIndex.add(newNode, I_PHON, WordUtils.lastSound(lts, RHYME_PHONES, inputArray[i - 1]));
                    }
                }
                // Add to working nodes
                workingNodes[i] = newNode;

                /* Create relationships, looking back.
                 * So for sentence A B C D:
                 * A -3-> D
                 * B -2-> D
                 * C -1-> D
                 */
                for (int r = i - Math.min(i, ORDER); r < i; r++) {
                    workingNodes[r].createRelationshipTo(workingNodes[i], RelTypes.values()[i - r]);
                }
            }
            tx.success();
        } finally {
            tx.finish();
        }

    }

    /**
     * Finds all rhyming nodes (regardless of end-property), and returns as a
     * String array.
     *
     * @param inputWord
     * @return
     */
    public String[] findRhymingWords(String inputWord) {
        return nodesToStringArray(findRhymingNodes(inputWord, false));
    }

    /**
     * Searches on the phoneme index to find words that rhyme with the
     * inputWord. If endNodesOnly is true, it will only search on words that end
     * a phrase.
     *
     * @param inputWord
     * @param endNodesOnly
     * @return
     */
    public Node[] findRhymingNodes(String inputWord, boolean endNodesOnly) {

        ArrayList<Node> rhymingNodes = new ArrayList<Node>();
        if (endNodesOnly) {
            //rhymeHits = nodeIndex.query(I_PHON + ":" + WordUtils.lastSound(lts, RHYME_PHONES, inputWord) + " AND " + I_END + ":" + "END");
            ExecutionResult rhymeResult = engine.execute("START a=node:node_idx(" + I_PHON + "=\"" + WordUtils.lastSound(lts, RHYME_PHONES, inputWord) + "\") MATCH a-[:ONE]->end WHERE end." + P_END + "! = \"END\" RETURN a");

            Iterator<Node> n_column = rhymeResult.columnAs("a");
            for (Node n : IteratorUtil.asIterable(n_column)) {
                rhymingNodes.add(n);
            }
        } else {
            IndexHits<Node> rhymeHits = nodeIndex.query(I_PHON, WordUtils.lastSound(lts, RHYME_PHONES, inputWord));

            for (Node n : rhymeHits) {
                rhymingNodes.add(n);
            }
        }

        return rhymingNodes.toArray(new Node[rhymingNodes.size()]);
    }

    public NodeSentence buildRhymingSentence(String inputWord) {
        Node[] rhymingEnds = findRhymingNodes(inputWord, true);

        if (rhymingEnds.length > 0) {
            Node[] sentenceOption = randomBuild(new Node[]{rhymingEnds[new Random().nextInt(rhymingEnds.length)]}, BuildDirection.BACKWARD_ONLY);
            return new NodeSentence(sentenceOption);
        } else {
            return null;
        }
    }

    public NodeSentence getRandomSentence() {
        //TODO Make this more random...

        ArrayList<ArrayList<Node>> seedOptions = getEndings();

        Node[] seedNodes = seedOptions.get(new Random().nextInt(seedOptions.size())).toArray(new Node[0]);

        Node[] resultNodes = randomBuild(seedNodes, BuildDirection.BOTH);

        return new NodeSentence(resultNodes);

    }

    //TODO: Allow specificaion of directoin (forward, back, both)
    private Node[] randomBuild(Node[] seedNodes, BuildDirection dir) {

        ArrayList<Node> workingNodes = new ArrayList<Node>(Arrays.asList(seedNodes));


        while ((dir.equals(BuildDirection.FORWARD_ONLY) || dir.equals(BuildDirection.BOTH))
                && !(workingNodes.get(workingNodes.size() - 1).hasProperty(P_END) && workingNodes.get(workingNodes.size() - 1).getProperty(P_END).equals("END"))) {
            ArrayList<Node> options = getFollowingNodes(workingNodes.subList(workingNodes.size() - Math.min(1, ORDER), workingNodes.size()).toArray(new Node[0]));
            // If we got nothing back, hard-break TODO: add "--" to these.
            if (options.isEmpty()) {
                break;
            }
            workingNodes.add(options.get(new Random().nextInt(options.size())));
        }

        while ((dir.equals(BuildDirection.BACKWARD_ONLY) || dir.equals(BuildDirection.BOTH))
                && !(workingNodes.get(0).hasProperty(P_END) && workingNodes.get(0).getProperty(P_END).equals("START"))) {
            ArrayList<Node> options = getPreceedingNodes(workingNodes.subList(0, Math.min(workingNodes.size(), ORDER)).toArray(new Node[0]));
            // If we got nothing back, hard-break TODO: add "--" to these.
            if (options.isEmpty()) {
                break;
            }
            workingNodes.add(0, options.get(new Random().nextInt(options.size())));
        }
        
        return workingNodes.toArray(new Node[workingNodes.size()]);
    }

    private ArrayList<Node> getFollowingNodes(Node[] currentNodes) {

        int comparisonOrder = Math.min(ORDER, currentNodes.length);

        ExecutionResult result;

        if (comparisonOrder == 1) {
            result = engine.execute("START a=node(" + currentNodes[0].getId() + ") MATCH a-[:ONE]->next RETURN next");
        } else {
            result = engine.execute("START a=node(" + currentNodes[currentNodes.length - 2].getId() + "),b=node(" + currentNodes[currentNodes.length - 1].getId() + ") MATCH a-[:ONE]->b-[:ONE]->next WHERE a-[:TWO]->next RETURN next");
        }

        ArrayList<Node> followingNodeOptions = new ArrayList<Node>();

        Iterator<Node> n_column = result.columnAs("next");
        for (Node n : IteratorUtil.asIterable(n_column)) {
            followingNodeOptions.add(n);
        }

        return followingNodeOptions;
    }

    private ArrayList<Node> getPreceedingNodes(Node[] currentNodes) {

        int comparisonOrder = Math.min(ORDER, currentNodes.length);
        ExecutionResult result;

        if (comparisonOrder == 1) {
            result = engine.execute("START a=node(" + currentNodes[0].getId() + ") MATCH pre-[:ONE]->a RETURN pre");
        } else {
            result = engine.execute("START a=node(" + currentNodes[0].getId() + "),b=node(" + currentNodes[1].getId() + ") MATCH pre-[:ONE]->a-[:ONE]->b WHERE pre-[:TWO]->b RETURN pre");
        }
        ArrayList<Node> followingNodeOptions = new ArrayList<Node>();

        Iterator<Node> n_column = result.columnAs("pre");
        for (Node n : IteratorUtil.asIterable(n_column)) {
            followingNodeOptions.add(n);
        }

        return followingNodeOptions;
    }

    //TODO Make this order-independent
    private ArrayList<ArrayList<Node>> getEndings() {
        ExecutionResult result = engine.execute("START end=node:node_idx(endnode='END') MATCH m2-[:ONE]->m1-[:ONE]->end WHERE m2-[:TWO]->end RETURN m2,m1,end");

        ArrayList<ArrayList<Node>> endNodeOptions = new ArrayList<ArrayList<Node>>();

        //endNodes
        for (Map<String, Object> row : result) {
            ArrayList<Node> endNodes = new ArrayList<Node>();
            for (Object n : row.values()) {
                endNodes.add((Node) n);
            }
            endNodeOptions.add(endNodes);
        }
        return endNodeOptions;
    }

    //
    //// Utility Methods
    //
    private static void registerShutdownHook(final GraphDatabaseService graphDb) {
        // Registers a shutdown hook for the Neo4j instance so that it
        // shuts down nicely when the VM exits (even if you "Ctrl-C" the
        // running example before it's completed)
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                graphDb.shutdown();
                System.out.println("Database shutdown.");
            }
        });
    }

    private String[] nodesToStringArray(Node[] inputNodeArray) {
        ArrayList<String> returnTokens = new ArrayList<String>();

        for (int i = 0; i < inputNodeArray.length; i++) {
            if (inputNodeArray[i].hasProperty(P_WORD)){// If there's no word on this node, don't try to string-ify it.  e.g. end nodes
                returnTokens.add((String) inputNodeArray[i].getProperty(P_WORD));
            }
        }

        return returnTokens.toArray(new String[returnTokens.size()]);
    }
}
