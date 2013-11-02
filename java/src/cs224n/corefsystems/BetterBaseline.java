package cs224n.corefsystems;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import cs224n.coref.ClusteredMention;
import cs224n.coref.Document;
import cs224n.coref.Entity;
import cs224n.coref.Mention;
import cs224n.coref.Sentence.Token;
import cs224n.util.Pair;
import cs224n.util.UnorderedPair;

public class BetterBaseline implements CoreferenceSystem {
	private HashSet<UnorderedPair<String, String>> heads;
	
	public BetterBaseline() {
		heads = new HashSet<UnorderedPair<String, String>>();
	}
	@Override
	public void train(Collection<Pair<Document, List<Entity>>> trainingData) {
		for (Pair<Document, List<Entity>> pair : trainingData) {
			List<Entity> clusters = pair.getSecond();
			for (Entity e : clusters) {
				for (Pair<Mention, Mention> mentionPair : e
						.orderedMentionPairs()) {
					heads.add(new UnorderedPair<String, String>(mentionPair.getFirst().headWord(),
							mentionPair.getSecond().headWord()));
				}
			}
		}
	}

	@Override
	public List<ClusteredMention> runCoreference(Document doc) {
		Map<Mention, Entity> clusters = new HashMap<Mention, Entity>();
		// (for each mention...)
		for (Mention m1 : doc.getMentions()) {
			Entity entity;
			if (clusters.containsKey(m1)) {
				entity = clusters.get(m1);
			} else {
				entity = m1.markSingleton().entity;
				clusters.put(m1, entity);
			}
			for (Mention m2 : doc.getMentions()) {
				UnorderedPair<String, String> pair = new UnorderedPair<String, String>(m1.headWord(), m2.headWord());
				if (heads.contains(pair) && !clusters.containsKey(m2)) {
					m2.markCoreferent(entity);
					clusters.put(m2, entity);
				}
			}
			
			
		}
		// (return the mentions)
		List<ClusteredMention> mentionsList = new ArrayList<ClusteredMention>();
		for (Mention mention:clusters.keySet()) {
			mentionsList.add(mention.markCoreferent(clusters.get(mention)));
		}
		return mentionsList;
	}

}
