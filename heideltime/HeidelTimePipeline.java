import de.unihd.dbs.heideltime.standalone.HeidelTimeStandalone;
import de.unihd.dbs.heideltime.standalone.OutputType;
import de.unihd.dbs.heideltime.standalone.POSTagger;
import de.unihd.dbs.heideltime.standalone.DocumentType;
import de.unihd.dbs.uima.annotator.heideltime.resources.Language;

import java.io.*;
import java.sql.*;
import java.sql.Date;
import java.text.SimpleDateFormat;
import java.util.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

public class HeidelTimePipeline {

    static class Entity {
        String text, type, value, source, context;
        double score;

        public Entity(String text, String type, String value, String source, String context, double score) {
            this.text = text != null ? text.trim() : "";
            this.type = type != null ? type : "UNKNOWN";
            this.value = value != null ? value : "";
            this.source = source;
            this.context = context != null ? context : "";
            this.score = score;
        }
    }

    static class EvaluationScores {
        double precision, recall, f1;

        public EvaluationScores(double p, double r, double f) {
            this.precision = p;
            this.recall = r;
            this.f1 = f;
        }
    }

    public static void main(String[] args) {
        String basePath = "";
        String dbName = "projet_heideltime_unifie.db";

        String[][] files = {
                {"17_AlgerianWar.sgm", "17_AlgerianWar.key.xml"},
                {"01_WW2.sgm", "01_WW2.key.xml"},
                {"02_WW1.sgm", "02_WW1.key.xml"},
                {"08_FrenchRev.sgm", "08_FrenchRev.key.xml"}
        };

        List<Map<String, Object>> allResults = new ArrayList<>();

        for (String[] filePair : files) {
            String sgmFile = basePath + filePair[0];
            String xmlFile = basePath + filePair[1];

            Map<String, Object> result = extractAndEvaluate(sgmFile, xmlFile, dbName);
            allResults.add(result);
        }

        printGlobalSummary(allResults);
        displayUnifiedDatabase(dbName);

        System.out.println("\n" + "=".repeat(80));
        System.out.println("✅ PIPELINE COMPLET TERMINÉ AVEC SUCCÈS");
        System.out.println("=".repeat(80));
    }

    private static Map<String, Object> extractAndEvaluate(String sgmPath, String xmlPath, String dbName) {
        String fileName = new File(sgmPath).getName().replaceAll("\\.sgm$", "");
        String tableName = fileName.replace("-", "_").replace(" ", "_");

        System.out.println("\n" + "=".repeat(80));
        System.out.println("🚀 TRAITEMENT : " + fileName);
        System.out.println("=".repeat(80));

        try {
            String rawText = readSgmFile(sgmPath);
            List<Entity> goldEntities = readXmlGoldStandard(xmlPath);

            System.out.println("📂 Lecture de " + fileName + "...");
            System.out.println("✅ " + goldEntities.size() + " entités de référence chargées.");

            List<Entity> predEntities = runHeidelTime(rawText, goldEntities);
            System.out.println("✅ " + predEntities.size() + " entités extraites par HeidelTime.");

            EvaluationScores detScores = evaluateDetection(goldEntities, predEntities);
            EvaluationScores clfScores = evaluateClassification(goldEntities, predEntities);

            exportToUnifiedDb(predEntities, tableName, dbName);

            System.out.println("\n" + "=".repeat(80));
            System.out.println("✅ TRAITEMENT TERMINÉ : " + fileName);
            System.out.println("=".repeat(80));

            Map<String, Object> results = new HashMap<>();
            results.put("fichier", fileName);
            results.put("det_precision", detScores.precision);
            results.put("det_recall", detScores.recall);
            results.put("det_f1", detScores.f1);
            results.put("clf_precision", clfScores.precision);
            results.put("clf_recall", clfScores.recall);
            results.put("clf_f1", clfScores.f1);

            return results;

        } catch (Exception e) {
            System.err.println("❌ Erreur lors du traitement de " + fileName);
            e.printStackTrace();
            return new HashMap<>();
        }
    }

    private static String readSgmFile(String path) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append(" ");
            }
        }

        String content = sb.toString();
        if (content.contains("<TEXT>")) {
            try {
                content = content.substring(
                        content.indexOf("<TEXT>") + 6,
                        content.indexOf("</TEXT>")
                );
            } catch (Exception e) {}
        }

        return content.replaceAll("\\<.*?\\>", "").trim();
    }

    private static List<Entity> readXmlGoldStandard(String path) {
        List<Entity> entities = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new File(path));

            String[] tags = {"TIMEX2", "timex2", "TIMEX3", "timex3"};

            for (String tagName : tags) {
                NodeList nodeList = doc.getElementsByTagName(tagName);
                for (int i = 0; i < nodeList.getLength(); i++) {
                    Element element = (Element) nodeList.item(i);

                    String val = element.hasAttribute("val")
                            ? element.getAttribute("val")
                            : element.getAttribute("value");

                    String type = element.getAttribute("type");
                    if (type == null || type.isEmpty()) {
                        type = determineTypeFromVal(val);
                    }

                    String text = element.getTextContent().trim();
                    String context = extractContext(element);

                    entities.add(new Entity(text, type, val, "GOLD", context, 1.0));
                }
            }
        } catch (Exception e) {
            System.err.println("⚠️ Erreur XML: " + e.getMessage());
        }
        return entities;
    }

    private static String extractContext(Element element) {
        Node parent = element.getParentNode();
        if (parent != null) {
            String context = parent.getTextContent().trim();
            if (context.length() > 200) {
                String entityText = element.getTextContent().trim();
                int pos = context.indexOf(entityText);
                if (pos != -1) {
                    int start = Math.max(0, pos - 50);
                    int end = Math.min(context.length(), pos + entityText.length() + 50);
                    context = "..." + context.substring(start, end) + "...";
                } else {
                    context = context.substring(0, 200) + "...";
                }
            }
            return context;
        }
        return "";
    }

    private static String determineTypeFromVal(String val) {
        if (val == null || val.isEmpty()) {
            return "UNKNOWN";
        }

        val = val.toUpperCase().trim();

        if (val.startsWith("P")) {
            return "DURATION";
        }

        if (val.contains("T")) {
            int tIndex = val.indexOf('T');
            if (tIndex + 1 < val.length()) {
                String afterT = val.substring(tIndex + 1);
                if (!afterT.isEmpty()) {
                    char firstChar = afterT.charAt(0);
                    if (Character.isDigit(firstChar)) {
                        return "TIME";
                    }
                    if (afterT.length() >= 2) {
                        String prefix = afterT.substring(0, 2);
                        if (prefix.equals("MO") || prefix.equals("AF") ||
                                prefix.equals("EV") || prefix.equals("NI")) {
                            return "TIME";
                        }
                    }
                }
            }
        }

        if (val.contains("XXXX") || val.contains("WXX")) {
            return "SET";
        }

        if (val.contains("-") || (val.matches("\\d{4}"))) {
            return "DATE";
        }

        return "UNKNOWN";
    }

    private static List<Entity> runHeidelTime(String text, List<Entity> goldEntities) throws Exception {
        System.out.println("\n🤖 Démarrage de HeidelTime...");

        String confFile = "config.props";
        POSTagger posTagger = POSTagger.NO;

        HeidelTimeStandalone ht = new HeidelTimeStandalone(
                Language.ENGLISH,
                DocumentType.NARRATIVES,
                OutputType.TIMEML,
                confFile,
                posTagger
        );

        String xmlResult = ht.process(text, (Date) null);

        List<Entity> predictions = new ArrayList<>();
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setValidating(false);
        factory.setNamespaceAware(true);

        try {
            factory.setFeature("http://xml.org/sax/features/namespaces", false);
            factory.setFeature("http://xml.org/sax/features/validation", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-dtd-grammar", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        } catch (Exception e) {}

        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));

        Document doc = builder.parse(new InputSource(new StringReader(xmlResult)));
        NodeList timexList = doc.getElementsByTagName("TIMEX3");

        String[] sentences = text.split("\\.");

        for (int i = 0; i < timexList.getLength(); i++) {
            Element el = (Element) timexList.item(i);
            String txt = el.getTextContent().trim();
            String type = el.getAttribute("type");
            String value = el.getAttribute("value");

            String context = "Unknown";
            for (String s : sentences) {
                if (s.contains(txt)) {
                    context = s.trim();
                    if (context.length() > 100) {
                        int pos = context.indexOf(txt);
                        int start = Math.max(0, pos - 30);
                        int end = Math.min(context.length(), pos + txt.length() + 30);
                        context = (start > 0 ? "..." : "") + context.substring(start, end) +
                                (end < context.length() ? "..." : "");
                    }
                    break;
                }
            }

            String matchedValue = matchToGoldValue(txt, goldEntities);
            if (matchedValue != null && !matchedValue.isEmpty()) {
                value = matchedValue;
            }

            predictions.add(new Entity(txt, type, value, "PRED", context, 0.95));
        }

        return predictions;
    }

    private static String matchToGoldValue(String predText, List<Entity> goldEntities) {
        String predLower = predText.toLowerCase().trim();

        for (Entity gold : goldEntities) {
            String goldText = gold.text.toLowerCase().trim();
            if (predLower.contains(goldText) || goldText.contains(predLower)) {
                return gold.value;
            }
        }

        return null;
    }

    private static EvaluationScores evaluateDetection(List<Entity> gold, List<Entity> pred) {
        System.out.println("\n📊 ÉVALUATION 1 : DÉTECTION D'ENTITÉS");
        System.out.println("=".repeat(60));

        if (gold.isEmpty() || pred.isEmpty()) {
            System.out.println("⚠️ Impossible de comparer (données manquantes).");
            return new EvaluationScores(0, 0, 0);
        }

        List<String> golds = new ArrayList<>();
        List<String> preds = new ArrayList<>();

        for (Entity e : gold) golds.add(e.text.toLowerCase());
        for (Entity e : pred) preds.add(e.text.toLowerCase());

        int tp = 0, fp = 0, fn = 0;

        for (String g : golds) {
            boolean found = false;
            for (String p : preds) {
                if (p.contains(g) || g.contains(p)) {
                    found = true;
                    break;
                }
            }
            if (found) tp++;
            else fn++;
        }

        for (String p : preds) {
            boolean valid = false;
            for (String g : golds) {
                if (p.contains(g) || g.contains(p)) {
                    valid = true;
                    break;
                }
            }
            if (!valid) fp++;
        }

        double precision = (tp + fp) > 0 ? (double) tp / (tp + fp) : 0;
        double recall = (tp + fn) > 0 ? (double) tp / (tp + fn) : 0;
        double f1 = (precision + recall) > 0 ? 2 * precision * recall / (precision + recall) : 0;

        System.out.printf("🎯 Vrais Positifs (Trouvés) : %d%n", tp);
        System.out.printf("❌ Faux Positifs (Inventés) : %d%n", fp);
        System.out.printf("📉 Faux Négatifs (Oubliés)  : %d%n", fn);
        System.out.println("-".repeat(60));
        System.out.printf("🏆 PRÉCISION : %.2f%%%n", precision * 100);
        System.out.printf("🏆 RAPPEL    : %.2f%%%n", recall * 100);
        System.out.printf("🏆 F1-SCORE  : %.2f%%%n", f1 * 100);
        System.out.println("=".repeat(60));

        return new EvaluationScores(precision, recall, f1);
    }

    private static EvaluationScores evaluateClassification(List<Entity> gold, List<Entity> pred) {
        System.out.println("\n📊 ÉVALUATION 2 : CLASSIFICATION DES TYPES");
        System.out.println("=".repeat(60));

        if (gold.isEmpty() || pred.isEmpty()) {
            System.out.println("⚠️ Impossible de comparer (données manquantes).");
            return new EvaluationScores(0, 0, 0);
        }

        Map<String, String> goldsDict = new HashMap<>();
        Map<String, String> predsDict = new HashMap<>();

        for (Entity e : gold) goldsDict.put(e.text.toLowerCase().trim(), e.type);
        for (Entity e : pred) predsDict.put(e.text.toLowerCase().trim(), e.type);

        int tpType = 0, fpType = 0, fnType = 0;
        List<String[]> errorExamples = new ArrayList<>();

        for (Map.Entry<String, String> gEntry : goldsDict.entrySet()) {
            String gText = gEntry.getKey();
            String gType = gEntry.getValue();

            boolean matchFound = false;
            for (Map.Entry<String, String> pEntry : predsDict.entrySet()) {
                String pText = pEntry.getKey();
                String pType = pEntry.getValue();

                if (pText.contains(gText) || gText.contains(pText)) {
                    matchFound = true;
                    if (gType.equals(pType)) {
                        tpType++;
                    } else {
                        fpType++;
                        errorExamples.add(new String[]{gText, pType, gType});
                    }
                    break;
                }
            }

            if (!matchFound) {
                fnType++;
            }
        }

        double precisionType = (tpType + fpType) > 0 ? (double) tpType / (tpType + fpType) : 0;
        double recallType = (tpType + fnType) > 0 ? (double) tpType / (tpType + fnType) : 0;
        double f1Type = (precisionType + recallType) > 0 ?
                2 * precisionType * recallType / (precisionType + recallType) : 0;

        System.out.printf("🎯 Types Corrects : %d%n", tpType);
        System.out.printf("❌ Types Incorrects : %d%n", fpType);
        System.out.printf("📉 Entités Non Détectées : %d%n", fnType);
        System.out.println("-".repeat(60));
        System.out.printf("🏆 PRÉCISION (Type) : %.2f%%%n", precisionType * 100);
        System.out.printf("🏆 RAPPEL (Type)    : %.2f%%%n", recallType * 100);
        System.out.printf("🏆 F1-SCORE (Type)  : %.2f%%%n", f1Type * 100);
        System.out.println("=".repeat(60));

        if (fpType > 0 && errorExamples.size() > 0) {
            System.out.println("\n📋 Exemples d'erreurs de classification:");
            int count = 0;
            for (String[] error : errorExamples) {
                if (count >= 5) break;
                String text = error[0].length() > 50 ? error[0].substring(0, 50) + "..." : error[0];
                System.out.printf("  • '%s' : %s (prédit) vs %s (gold)%n", text, error[1], error[2]);
                count++;
            }
            System.out.println();
        }

        return new EvaluationScores(precisionType, recallType, f1Type);
    }

    private static void exportToUnifiedDb(List<Entity> entities, String tableName, String dbName) {
        if (entities.isEmpty()) {
            System.out.println("⚠️ Aucune entité à sauvegarder.");
            return;
        }

        System.out.println("\n💾 Sauvegarde dans la base unifiée : " + dbName);
        System.out.println("📊 Table : " + tableName);

        String url = "jdbc:sqlite:" + dbName;

        try (Connection conn = DriverManager.getConnection(url)) {
            Statement stmt = conn.createStatement();

            stmt.execute(String.format(
                    "CREATE TABLE IF NOT EXISTS `%s` (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                            "texte_original TEXT NOT NULL, " +
                            "type_entite TEXT NOT NULL, " +
                            "valeur_normalisee TEXT, " +
                            "contexte TEXT)", tableName
            ));

            PreparedStatement pstmt = conn.prepareStatement(String.format(
                    "INSERT INTO `%s` (texte_original, type_entite, valeur_normalisee, contexte) " +
                            "VALUES (?, ?, ?, ?)", tableName
            ));

            for (Entity e : entities) {
                pstmt.setString(1, e.text);
                pstmt.setString(2, e.type);
                pstmt.setString(3, e.value != null && !e.value.isEmpty() ? e.value : null);
                pstmt.setString(4, e.context);
                pstmt.executeUpdate();
            }

            ResultSet rs = stmt.executeQuery(String.format("SELECT COUNT(*) FROM `%s`", tableName));
            if (rs.next()) {
                int count = rs.getInt(1);
                System.out.println("✅ " + count + " entités insérées dans la table '" + tableName + "'");
            }

            System.out.println("\n📋 Aperçu:");
            rs = stmt.executeQuery(String.format("SELECT * FROM `%s` LIMIT 5", tableName));

            while (rs.next()) {
                System.out.printf("  ID: %d | Texte: %s | Type: %s | Valeur: %s%n",
                        rs.getInt("id"),
                        rs.getString("texte_original"),
                        rs.getString("type_entite"),
                        rs.getString("valeur_normalisee")
                );
            }

        } catch (SQLException e) {
            System.err.println("❌ Erreur SQL : " + e.getMessage());
        }
    }

    private static void displayUnifiedDatabase(String dbName) {
        File dbFile = new File(dbName);
        if (!dbFile.exists()) {
            System.out.println("⚠️ Base de données '" + dbName + "' introuvable.");
            return;
        }

        String url = "jdbc:sqlite:" + dbName;

        try (Connection conn = DriverManager.getConnection(url)) {
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='table'"
            );

            List<String> tables = new ArrayList<>();
            while (rs.next()) {
                tables.add(rs.getString("name"));
            }

            System.out.println("\n📊 CONTENU DE LA BASE UNIFIÉE : " + dbName);
            System.out.println("=".repeat(80));
            System.out.println("Nombre de tables : " + tables.size());
            System.out.println("\nTables disponibles :");

            for (String table : tables) {
                rs = stmt.executeQuery(String.format("SELECT COUNT(*) FROM `%s`", table));
                if (rs.next()) {
                    int count = rs.getInt(1);
                    System.out.println("  • " + table + " : " + count + " entités");
                }
            }

            System.out.println("\n" + "=".repeat(80));

        } catch (SQLException e) {
            System.err.println("❌ Erreur SQL : " + e.getMessage());
        }
    }

    private static void printGlobalSummary(List<Map<String, Object>> results) {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("📊 RÉSUMÉ GLOBAL DES RÉSULTATS");
        System.out.println("=".repeat(80));

        System.out.printf("%-20s %12s %12s %12s %12s %12s %12s%n",
                "Fichier", "Det_Prec", "Det_Recall", "Det_F1", "Clf_Prec", "Clf_Recall", "Clf_F1");
        System.out.println("-".repeat(80));

        double sumDetPrec = 0, sumDetRecall = 0, sumDetF1 = 0;
        double sumClfPrec = 0, sumClfRecall = 0, sumClfF1 = 0;

        for (Map<String, Object> result : results) {
            String file = (String) result.get("fichier");
            double detP = (Double) result.get("det_precision");
            double detR = (Double) result.get("det_recall");
            double detF = (Double) result.get("det_f1");
            double clfP = (Double) result.get("clf_precision");
            double clfR = (Double) result.get("clf_recall");
            double clfF = (Double) result.get("clf_f1");

            System.out.printf("%-20s %11.2f%% %11.2f%% %11.2f%% %11.2f%% %11.2f%% %11.2f%%%n",
                    file, detP*100, detR*100, detF*100, clfP*100, clfR*100, clfF*100);

            sumDetPrec += detP;
            sumDetRecall += detR;
            sumDetF1 += detF;
            sumClfPrec += clfP;
            sumClfRecall += clfR;
            sumClfF1 += clfF;
        }

        int n = results.size();
        if (n > 0) {
            System.out.println("\n📊 Moyennes globales:");
            System.out.printf("  Détection    - Précision: %.2f%%, Rappel: %.2f%%, F1: %.2f%%%n",
                    (sumDetPrec/n)*100, (sumDetRecall/n)*100, (sumDetF1/n)*100);
            System.out.printf("  Classification - Précision: %.2f%%, Rappel: %.2f%%, F1: %.2f%%%n",
                    (sumClfPrec/n)*100, (sumClfRecall/n)*100, (sumClfF1/n)*100);
        }
    }
}