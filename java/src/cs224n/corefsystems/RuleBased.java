package cs224n.corefsystems;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import cs224n.coref.ClusteredMention;
import cs224n.coref.Document;
import cs224n.coref.Entity;
import cs224n.coref.Mention;
import cs224n.coref.Name;
import cs224n.coref.Pronoun;
import cs224n.coref.Pronoun.Speaker;
import cs224n.coref.Sentence;
import cs224n.coref.Util;
import cs224n.ling.Constituent;
import cs224n.ling.Tree;
import cs224n.util.Pair;
import cs224n.util.UnorderedPair;

public class RuleBased implements CoreferenceSystem {
	private Set<UnorderedPair<String, String>> candidates;
	private HashSet<UnorderedPair<String, String>> heads;

	public RuleBased() {
		candidates = new HashSet<UnorderedPair<String, String>>();
		heads = new HashSet<UnorderedPair<String, String>>();
	}

	@Override
	public void train(Collection<Pair<Document, List<Entity>>> trainingData) {
		for (Pair<Document, List<Entity>> pair : trainingData) {
			List<Entity> clusters = pair.getSecond();
			for (Entity e : clusters) {
				for (Pair<Mention, Mention> mentionPair : e
						.orderedMentionPairs()) {
					heads.add(new UnorderedPair<String, String>(mentionPair
							.getFirst().headWord(), mentionPair.getSecond()
							.headWord()));
				}
			}
		}

	}

	@Override
	public List<ClusteredMention> runCoreference(Document doc) {
		Sentence prev = null;
		for (Sentence sentence : doc.sentences) {
			Hobbs(sentence, prev);
			prev = sentence;
		}
		List<ClusteredMention> mentions = new ArrayList<ClusteredMention>();
		ArrayList<Set<Mention>> clusters = new ArrayList<Set<Mention>>();
		// (for each mention...)
		Map<String, Set<Mention>> exact_clusters = new HashMap<String, Set<Mention>>();
		for (Mention m : doc.getMentions()) {
			// (...get its text)
			String mentionString = m.gloss();
			// (...if we've seen this text before...)
			if (exact_clusters.containsKey(mentionString)) {
				// (...add it to the cluster)
				exact_clusters.get(mentionString).add(m);
				//System.out.println("Exact word matched! " + m.toString());
			} else {
				Set<Mention> mm = new HashSet<Mention>();
				mm.add(m);
				clusters.add(mm);
				exact_clusters.put(mentionString, mm);
			}
		}

		// (Head token match)
		for (int i = 0; i < clusters.size() - 1; i++) {
			Set<Mention> mm1 = clusters.get(i);
			int j = i + 1;
			while (j < clusters.size()) {
				Set<Mention> mm2 = clusters.get(j);
				boolean merge = false;
				for (Mention m1 : mm1) {
					for (Mention m2 : mm2) {
						if (m1.headWord().equals(m2.headWord())) {
							merge = true;
							//System.out.println("Head word matched! " + m1.toString() + " " + m2.toString());
							break;
						}
					}
					if (merge) break;
				}
				if (merge) {
					
					mm1.addAll(mm2);
					clusters.remove(j);
				} else {
					j++;
				}
			}
		}

		for (int i = 0; i < clusters.size() - 1; i++) {
			Set<Mention> mm1 = clusters.get(i);
			int j = i + 1;
			while (j < clusters.size()) {
				Set<Mention> mm2 = clusters.get(j);
				boolean merge = false;
				Pair<Boolean, Boolean> truePair = Pair.make(true, true);
				for (Mention m1 : mm1) {
					for (Mention m2 : mm2) {
						if (Math.abs(doc.indexOfSentence(m1.sentence)
								- doc.indexOfSentence(m2.sentence)) <= 2) {
							if (heads.contains(UnorderedPair.make(
									m1.headWord(), m2.headWord()))) {
								merge = true;
								break;
							}
							for (UnorderedPair<String, String> pair : candidates)
								if (pair.getFirst().indexOf(m1.gloss()) >= 0
										&& pair.getSecond().indexOf(m2.gloss()) >= 0
										&& (doc.indexOfSentence(m1.sentence)
												- doc.indexOfSentence(m2.sentence) >= -3 || doc
												.indexOfSentence(m1.sentence)
												- doc.indexOfSentence(m2.sentence) == 0)) {
									if (Util.haveGenderAndAreSameGender(m1, m2)
											.equals(truePair)
											&& Util.haveNumberAndAreSameNumber(
													m1, m2).equals(truePair) &&
													((m1.headWord().toLowerCase().equals("i") || m1.headWord().toLowerCase().equals("you"))
															&& m2.headWord().toLowerCase().equals("it"))) {
										merge = true;
										//System.out.println("Hobbs 1 matched! " + m1.toString() + " " + m2.toString());
										break;
									}
									Pronoun pron = Pronoun.valueOrNull(m2
											.headWord());
									if (pron == null)
										continue;
									if (Name.isName(m1.headWord()) && (pron.type.equals(Pronoun.Type.POSESSIVE_PRONOUN) ||
											pron.type.equals(Pronoun.Type.POSESSIVE_DETERMINER))) {
										merge = true;
										//System.out.println("Hobbs 3 matched! " + m1.toString() + " " + m2.toString());
										break;
									}
									if (m1.headToken().isNoun()
											&& !Name.isName(m1.gloss())
											&& Util.haveNumberAndAreSameNumber(
													m1, m2).equals(truePair)
											&& (pron.equals(Pronoun.IT)
													|| pron.equals(Pronoun.ITS)
													|| pron.equals(Pronoun.ITSELF)
													|| pron.equals(Pronoun.THEY)
													|| pron.equals(Pronoun.THEM)
													|| pron.equals(Pronoun.THEIRS)
													|| pron.equals(Pronoun.THEMSELVES)
													|| pron.equals(Pronoun.THEIRSELVES) || pron
														.equals(Pronoun.THEIR))) {
										merge = true;
										//System.out.println("Hobbs 2 matched! " + m1.toString() + " " + m2.toString());
										break;
									}
								}
						}
					}
					if (merge)
						break;
				}
				if (merge) {
					mm1.addAll(mm2);
					clusters.remove(j);
				} else {
					j++;
				}
			}
		}

		// Quoted rule
		for (int i = 0; i < clusters.size() - 1; i++) {
			Set<Mention> mm1 = clusters.get(i);
			int j = i + 1;
			while (j < clusters.size()) {
				Set<Mention> mm2 = clusters.get(j);
				boolean merge = false;
				for (Mention m1 : mm1) {
					for (Mention m2 : mm2) {
						if (m1.headToken().isQuoted()
								&& m2.headToken().isQuoted()
								&& Math.abs(doc.indexOfSentence(m1.sentence)
										- doc.indexOfSentence(m2.sentence)) == 1) {
							Pronoun pron1 = Pronoun.valueOrNull(m1.headWord());
							Pronoun pron2 = Pronoun.valueOrNull(m2.headWord());
							if (pron1 == null || pron2 == null)
								continue;
							if (((pron1.speaker == Speaker.FIRST_PERSON && pron2.speaker == Speaker.SECOND_PERSON) || (pron2.speaker == Speaker.FIRST_PERSON && pron1.speaker == Speaker.SECOND_PERSON))) {
								//System.out.println("Speaker matched! " + m1.toString() + " " + m2.toString());
								merge = true;
								break;
							}
						}
					}
					if (merge)
						break;
				}
				if (merge) {
					mm1.addAll(mm2);
					clusters.remove(j);
				} else {
					j++;
				}
			}
		}

		List<Entity> entities = new ArrayList<Entity>();
		for (Set<Mention> mm : clusters) {
			Entity entity = new Entity(doc.getMentions(), mm);
			entities.add(entity);
		}
		Map<Mention, Entity> me = Entity.mentionToEntityMap(entities);
		for (Mention mention : me.keySet()) {
			mentions.add(mention.markCoreferent(me.get(mention)));
		}
		return mentions;

	}

	private void Hobbs(Sentence cur, Sentence prev) {
		int index = 0;
		List<Tree<String>> nodes = cur.parse.toSubTreeList();
		for (Tree<String> node : nodes) {
			node.setUniqueIndex(index);
			index++;
		}
		index = 0;
		int np_start = 0;
		List<Constituent<String>> constituents = cur.parse.toConstituentList();
		for (String word : cur.words) {
			if (Pronoun.isSomePronoun(word)) {
				String state = "-";
				List<Pair<String, Integer>> path = cur.parse.pathToIndex(index);
				Pronoun pron = Pronoun.valueOrNull(word);
				if (pron == null)
					continue;
				for (int i = path.size() - 1; i >= 0; i--) {
					if (path.get(i).getFirst().equals("NP")
							|| path.get(i).getFirst().equals("S")) {
						if (state.equals("-")) {
							if (path.get(i).getFirst().equals("NP")) {
								state = "Step2";
								Tree<String> node = nodes.get(path.get(i)
										.getSecond());
								for (String w : node.getYield()) {
									np_start++;
									if (w.equals(word)) {
										np_start = index - np_start;
									}
								}
							}
							continue;
						}

						Tree<String> node = nodes.get(path.get(i).getSecond());
						int start = index - node.getYield().size();
						if (state.equals("Step2")) {
							for (Constituent<String> c : constituents) {
								for (Constituent<String> c1 : constituents) {
									if (c.getStart() >= start
											&& c.getEnd() < c1.getStart()
											&& c.getLabel().equals("NP")
											&& c1.getLabel().equals("NP")
											&& c1.getEnd() <= np_start
											&& !pron.type
													.equals(Pronoun.Type.REFLEXIVE)) {
										String phrase = "";
										for (int j = c.getStart(); j < c
												.getEnd(); j++) {
											if (j == c.getEnd() - 1)
												phrase += cur.words.get(j);
											else
												phrase += cur.words.get(j)
														+ " ";
										}
										candidates
												.add(new UnorderedPair<String, String>(
														phrase, word));
									}
								}
								String phrase = "";
								if (c.getEnd() >= start
										&& c.getEnd() <= index
										&& pron.type
												.equals(Pronoun.Type.REFLEXIVE)) {
									for (int j = c.getStart(); j < c.getEnd(); j++) {
										if (j == c.getEnd() - 1)
											phrase += cur.words.get(j);
										else
											phrase += cur.words.get(j) + " ";
									}
									candidates
											.add(new UnorderedPair<String, String>(
													phrase, word));
								}
							}
							state = "Step3";
						} else if (state.equals("Step3")) {
							// Step 7
							if (path.get(i).getFirst().equals("NP")) {
								Tree<String> prev_node = nodes.get(path.get(
										i + 1).getSecond());
								Queue<Tree<String>> queue = new ArrayDeque<Tree<String>>();
								for (Tree<String> n : node.getChildren()) {
									if (n.getUniqueIndex() == prev_node
											.getUniqueIndex())
										break;
									queue.add(n);
								}
								while (!queue.isEmpty()) {
									Tree<String> current = queue.poll();
									if (current.getLabel().equals("NP")
											&& !pron.type
													.equals(Pronoun.Type.REFLEXIVE)) {
										String phrase = "";
										for (String w : current.getYield()) {
											phrase += w + " ";
										}
										candidates
												.add(new UnorderedPair<String, String>(
														phrase, word));
									}
									for (Tree<String> c : current.getChildren()) {
										queue.add(c);
									}
								}
							} else {
								// Step 8
								Tree<String> prev_node = nodes.get(path.get(
										i + 1).getSecond());
								boolean seen = false;
								Queue<Tree<String>> queue = new ArrayDeque<Tree<String>>();
								for (Tree<String> n : node.getChildren()) {
									if (seen)
										queue.add(n);
									if (n.getUniqueIndex() == prev_node
											.getUniqueIndex())
										seen = true;
								}
								while (!queue.isEmpty()) {
									Tree<String> current = queue.poll();
									if (current.getLabel().equals("NP")
											&& !pron.type
													.equals(Pronoun.Type.REFLEXIVE)) {
										String phrase = "";
										for (String w : current.getYield()) {
											phrase += w + " ";
										}
										phrase = phrase.substring(0,
												phrase.length() - 1);
										candidates
												.add(new UnorderedPair<String, String>(
														phrase, word));
									}
									if (!current.getLabel().equals("NP")
											&& !current.getLabel().equals("S"))
										for (Tree<String> c : current
												.getChildren()) {
											queue.add(c);
										}
								}
							}
						}
					}
				}

				if (prev != null) {
					Queue<Tree<String>> queue = new ArrayDeque<Tree<String>>();
					queue.add(prev.parse);
					while (!queue.isEmpty()) {
						Tree<String> current = queue.poll();
						if (current.getLabel().equals("NP")
								&& !pron.type.equals(Pronoun.Type.REFLEXIVE)) {
							String phrase = "";
							for (String w : current.getYield()) {
								phrase += w + " ";
							}
							phrase = phrase.substring(0, phrase.length() - 1);
							candidates.add(new UnorderedPair<String, String>(
									phrase, word));
						}
						if (!current.getLabel().equals("NP")
								&& !current.getLabel().equals("S"))
							for (Tree<String> c : current.getChildren()) {
								queue.add(c);
							}
					}
				}
			}
			index++;

		}

	}

}
