package cs224n.corefsystems;

import cs224n.coref.*;
import cs224n.coref.Feature.Path;
import cs224n.util.Pair;
import cs224n.util.UnorderedPair;
import edu.stanford.nlp.classify.LinearClassifier;
import edu.stanford.nlp.classify.LinearClassifierFactory;
import edu.stanford.nlp.classify.RVFDataset;
import edu.stanford.nlp.ling.RVFDatum;
import edu.stanford.nlp.stats.Counter;
import edu.stanford.nlp.util.Triple;
import edu.stanford.nlp.util.logging.RedwoodConfiguration;
import edu.stanford.nlp.util.logging.StanfordRedwoodConfiguration;

import java.text.DecimalFormat;
import java.util.*;

import static edu.stanford.nlp.util.logging.Redwood.Util.*;

/**
 * @author Gabor Angeli (angeli at cs.stanford)
 */
public class ClassifierBased implements CoreferenceSystem {

	private static <E> Set<E> mkSet(E[] array) {
		Set<E> rtn = new HashSet<E>();
		Collections.addAll(rtn, array);
		return rtn;
	}

	private static final Set<Object> ACTIVE_FEATURES = mkSet(new Object[] {

	/*
	 * TODO: Create a set of active features
	 */

	Feature.ExactMatch.class,
			Feature.HeadMatch.class,
			Feature.GenderMatch.class,
			Feature.NumberMatch.class,
			Feature.SentenceDistance.class,
			Feature.MentionDistance.class,
			Feature.Path.class,
			// skeleton for how to create a pair feature
			Pair.make(Feature.IsCandidatePron.class, Feature.IsFixedPron.class), });

	private LinearClassifier<Boolean, Feature> classifier;

	public ClassifierBased() {
		StanfordRedwoodConfiguration.setup();
		RedwoodConfiguration.current().collapseApproximate().apply();
	}

	public FeatureExtractor<Pair<Mention, ClusteredMention>, Feature, Boolean> extractor = new FeatureExtractor<Pair<Mention, ClusteredMention>, Feature, Boolean>() {
		private <E> Feature feature(Class<E> clazz,
				Pair<Mention, ClusteredMention> input, Option<Double> count) {

			// --Variables
			Mention onPrix = input.getFirst(); // the first mention (referred to
												// as m_i in the handout)
			Mention candidate = input.getSecond().mention; // the second mention
															// (referred to as
															// m_j in the
															// handout)
			Entity candidateCluster = input.getSecond().entity; // the cluster
																// containing
																// the second
																// mention

			// --Features
			if (clazz.equals(Feature.ExactMatch.class)) {
				// (exact string match)
				return new Feature.ExactMatch(onPrix.gloss().equals(
						candidate.gloss()));
			} else if (clazz.equals(Feature.HeadMatch.class)) {
				return new Feature.HeadMatch(onPrix.headWord().equals(
						candidate.headWord()));
			} else if (clazz.equals(Feature.GenderMatch.class)) {
				return new Feature.GenderMatch(Util.haveGenderAndAreSameGender(
						onPrix, candidateCluster) == Pair.make(true, true));
			} else if (clazz.equals(Feature.NumberMatch.class)) {
				return new Feature.NumberMatch(Util.haveNumberAndAreSameNumber(
						onPrix, candidateCluster) == Pair.make(true, true));
			} else if (clazz.equals(Feature.SentenceDistance.class)) {
				return new Feature.SentenceDistance(Math.abs(onPrix.doc
						.indexOfSentence(onPrix.sentence)
						- candidate.doc.indexOfSentence(candidate.sentence)));
			} else if (clazz.equals(Feature.MentionDistance.class)) {
				return new Feature.MentionDistance(Math.abs(onPrix.doc
						.indexOfMention(onPrix)
						- candidate.doc.indexOfMention(candidate)));
			} else if (clazz.equals(Feature.IsCandidatePron.class)) {
				return new Feature.IsCandidatePron(
						Pronoun.isSomePronoun(candidate.gloss()));
			} else if (clazz.equals(Feature.Path.class)) {
				if (onPrix.sentence == candidate.sentence
						&& onPrix.headWordIndex != candidate.headWordIndex) {
					Sentence sentence = onPrix.sentence;
					String path = "";
					Iterable<Pair<String, Integer>> paths = sentence.parse
							.getTraversalBetween(onPrix.headWordIndex,
									candidate.headWordIndex);
					if (paths == null)
						return new Feature.Path(path);
					for (Pair<String, Integer> pair : paths) {
						path += "/" + pair.getFirst();
					}
					return new Feature.Path(path);
				}
				return new Feature.Path("No Path");
			} else if (clazz.equals(Feature.IsFixedPron.class)) {
				return new Feature.IsFixedPron(Pronoun.isSomePronoun(onPrix
						.gloss()));
			} else {
				throw new IllegalArgumentException("Unregistered feature: "
						+ clazz);
			}
		}

		@SuppressWarnings({ "unchecked" })
		@Override
		protected void fillFeatures(Pair<Mention, ClusteredMention> input,
				Counter<Feature> inFeatures, Boolean output,
				Counter<Feature> outFeatures) {
			// --Input Features
			for (Object o : ACTIVE_FEATURES) {
				if (o instanceof Class) {
					// (case: singleton feature)
					Option<Double> count = new Option<Double>(1.0);
					Feature feat = feature((Class) o, input, count);
					if (count.get() > 0.0) {
						inFeatures.incrementCount(feat, count.get());
					}
				} else if (o instanceof Pair) {
					// (case: pair of features)
					Pair<Class, Class> pair = (Pair<Class, Class>) o;
					Option<Double> countA = new Option<Double>(1.0);
					Option<Double> countB = new Option<Double>(1.0);
					Feature featA = feature(pair.getFirst(), input, countA);
					Feature featB = feature(pair.getSecond(), input, countB);
					if (countA.get() * countB.get() > 0.0) {
						inFeatures.incrementCount(new Feature.PairFeature(
								featA, featB), countA.get() * countB.get());
					}
				}
			}

			// --Output Features
			if (output != null) {
				outFeatures.incrementCount(new Feature.CoreferentIndicator(
						output), 1.0);
			}
		}

		@Override
		protected Feature concat(Feature a, Feature b) {
			return new Feature.PairFeature(a, b);
		}
	};

	public void train(Collection<Pair<Document, List<Entity>>> trainingData) {
		startTrack("Training");
		// --Variables
		RVFDataset<Boolean, Feature> dataset = new RVFDataset<Boolean, Feature>();
		LinearClassifierFactory<Boolean, Feature> fact = new LinearClassifierFactory<Boolean, Feature>();
		// --Feature Extraction
		startTrack("Feature Extraction");
		for (Pair<Document, List<Entity>> datum : trainingData) {
			// (document variables)
			Document doc = datum.getFirst();
			List<Entity> goldClusters = datum.getSecond();
			List<Mention> mentions = doc.getMentions();
			Map<Mention, Entity> goldEntities = Entity
					.mentionToEntityMap(goldClusters);
			startTrack("Document " + doc.id);
			// (for each mention...)
			for (int i = 0; i < mentions.size(); i++) {
				// (get the mention and its cluster)
				Mention onPrix = mentions.get(i);
				Entity source = goldEntities.get(onPrix);
				if (source == null) {
					throw new IllegalArgumentException(
							"Mention has no gold entity: " + onPrix);
				}
				// (for each previous mention...)
				int oldSize = dataset.size();
				for (int j = i - 1; j >= 0; j--) {
					// (get previous mention and its cluster)
					Mention cand = mentions.get(j);
					Entity target = goldEntities.get(cand);
					if (target == null) {
						throw new IllegalArgumentException(
								"Mention has no gold entity: " + cand);
					}
					// (extract features)
					Counter<Feature> feats = extractor.extractFeatures(Pair
							.make(onPrix, cand.markCoreferent(target)));
					// (add datum)
					dataset.add(new RVFDatum<Boolean, Feature>(feats,
							target == source));
					// (stop if
					if (target == source) {
						break;
					}
				}
				// logf("Mention %s (%d datums)", onPrix.toString(),
				// dataset.size() - oldSize);
			}
			endTrack("Document " + doc.id);
		}
		endTrack("Feature Extraction");
		// --Train Classifier
		startTrack("Minimizer");
		this.classifier = fact.trainClassifier(dataset);
		endTrack("Minimizer");
		// --Dump Weights
		startTrack("Features");
		// (get labels to print)
		Set<Boolean> labels = new HashSet<Boolean>();
		labels.add(true);
		// (print features)
		for (Triple<Feature, Boolean, Double> featureInfo : this.classifier
				.getTopFeatures(labels, 0.0, true, 100, true)) {
			Feature feature = featureInfo.first();
			Boolean label = featureInfo.second();
			Double magnitude = featureInfo.third();
			// log(FORCE,new DecimalFormat("0.000").format(magnitude) + " [" +
			// label + "] " + feature);
		}
		end_Track("Features");
		endTrack("Training");
	}

	public List<ClusteredMention> runCoreference(Document doc) {
		// --Overhead
		startTrack("Testing " + doc.id);
		// (variables)
		List<ClusteredMention> rtn = new ArrayList<ClusteredMention>(doc
				.getMentions().size());
		List<Mention> mentions = doc.getMentions();
		int singletons = 0;
		// --Run Classifier
		for (int i = 0; i < mentions.size(); i++) {
			// (variables)
			Mention onPrix = mentions.get(i);
			int coreferentWith = -1;
			// (get mention it is coreferent with)
			for (int j = i - 1; j >= 0; j--) {
				ClusteredMention cand = rtn.get(j);
				boolean coreferent = classifier
						.classOf(new RVFDatum<Boolean, Feature>(extractor
								.extractFeatures(Pair.make(onPrix, cand))));
				if (coreferent) {
					coreferentWith = j;
					break;
				}
			}
			// (mark coreference)
			if (coreferentWith < 0) {
				singletons += 1;
				rtn.add(onPrix.markSingleton());
			} else {
				// log("Mention " + onPrix + " coreferent with " +
				// mentions.get(coreferentWith));
				rtn.add(onPrix.markCoreferent(rtn.get(coreferentWith)));
			}
		}
		// log("" + singletons + " singletons");
		// --Return
		endTrack("Testing " + doc.id);
		return rtn;
	}

	private class Option<T> {
		private T obj;

		public Option(T obj) {
			this.obj = obj;
		}

		public Option() {
		};

		public T get() {
			return obj;
		}

		public void set(T obj) {
			this.obj = obj;
		}

		public boolean exists() {
			return obj != null;
		}
	}
}
