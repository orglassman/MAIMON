package uwdb.discovery.dependency.approximate.common.dependency;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;

import uwdb.discovery.dependency.approximate.common.Constants;
import uwdb.discovery.dependency.approximate.common.sets.AttributeSet;
import uwdb.discovery.dependency.approximate.common.sets.IAttributeSet;
import uwdb.discovery.dependency.approximate.entropy.MasterCompressedDB;

public class AcyclicSchema {
	
	private Set<JoinDependency> JDs;
	private int numAtts;
	double totalEntropy;
	private JTNode JoinTreeRepresentation;
	HashSet<IAttributeSet> JoinTreeClusters;
	
	public static class compatibilityStatus{		
		boolean JD1SplitsJD2;
		boolean JD2SeperatesComponentOfJD1;
		
	}
	
	//returns true if JD1 and JD2 are NOT conflict-free according
	//to the first characterization of Beeri et al.
	public static boolean splitBy(JoinDependency JD1, JoinDependency JD2, compatibilityStatus status) {
		//either same JD or two JDs with same LHS which cannot be compatible
		if(JD1.getlhs().equals(JD2.getlhs())) {
			status.JD1SplitsJD2=true;
			return true;
		}
		// This means that JD2.getlhs()\cap Xj=0 for all components Xj.		
		//and that they are compatible
		if(JD1.getlhs().contains(JD2.getlhs())) {
			status.JD1SplitsJD2=false;
			status.JD2SeperatesComponentOfJD1=false;
			return false;
		}
		
		
		IAttributeSet JD1lhsCloned = JD1.getlhs().clone();
		IAttributeSet JD2lhs = JD2.getlhs();		
		boolean foundComponent = false;
		
		for(IAttributeSet component: JD1.getComponents()) {
			if(component.intersects(JD2lhs)) {
				JD1lhsCloned.or(component);
				if(JD1lhsCloned.contains(JD2lhs)) {					
					foundComponent = true;
					//now make sure that this component is separable by JD2
					//this is the case only if this component intersects at least
					//two components of JD2
					int JD2ComponentsIntersection =0;
					for(IAttributeSet JD2Component: JD2.getComponents()) {
						if(JD1lhsCloned.intersects(JD2Component))
							JD2ComponentsIntersection++;
					}
					if(JD2ComponentsIntersection<2) {
						status.JD2SeperatesComponentOfJD1=false;
						return false;		//not conflict-free
					}
					else {
						status.JD2SeperatesComponentOfJD1=true;
						return false;	
					}
					
				}
				else {				
					status.JD1SplitsJD2=true;
					return true; //intersect but not contains means separation because JD2.getlhs() \not\contained JD1.getlhs()
				}
	//			JD1lhsCloned.intersectNonConst(JD1.getlhs());
			}			
		}
		return !foundComponent;
		
	}
	
	
	public static boolean isCompatible(JoinDependency JD1, JoinDependency JD2) {
		//a more restrictive condition to the intersection property by Beeri et al.
	/*	if(JD1.getlhs().intersects(JD2.getlhs()) && !JD1.getlhs().contains(JD2.getlhs())
				&& !JD2.getlhs().contains(JD1.getlhs()))
			return false;
		*/
		compatibilityStatus cs12 = new AcyclicSchema.compatibilityStatus();
		boolean JD1splitJD2 = splitBy(JD1,JD2,cs12);
		if(cs12.JD1SplitsJD2)
			return false;
		compatibilityStatus cs21 = new compatibilityStatus();
		boolean JD2splitJD1 = splitBy(JD2,JD1,cs21);
		if(cs21.JD1SplitsJD2)
			return false;
		return (cs12.JD2SeperatesComponentOfJD1 && cs21.JD2SeperatesComponentOfJD1);
		//return (!splitBy(JD1, JD2) && !splitBy(JD2, JD1));
	}
	
	
	
	public AcyclicSchema(int numAtts, double totalEntropy) {
		JDs = new HashSet<JoinDependency>();
		this.numAtts = numAtts;
		this.totalEntropy = totalEntropy;
		JoinTreeClusters = new HashSet<IAttributeSet>();
	}
	
	
	class sortBySepSize implements Comparator<JoinDependency>{

		@Override
		public int compare(JoinDependency arg0, JoinDependency arg1) {
			int subtractLHS = arg0.getlhs().cardinality()-arg1.getlhs().cardinality();
			//if(subtractLHS != 0)
			return subtractLHS;
			/*
			if(arg1.getlhs().contains(arg0.getlhs()))
				return -1;
			if(arg0.getlhs().contains(arg1.getlhs()))
				return 1;
			IAttributeSet componentThatContainsArg0LHS = arg1.getlhs().clone();
			//find the component in arg1 that contains arg0.getLHS()
			for(IAttributeSet arg1Component: arg1.getComponents()) {
				if(arg1Component.intersects(arg0.getlhs())) {
					componentThatContainsArg0LHS.or(arg1Component);
					break;
				}
			}
			IAttributeSet componentThatContainsArg1LHS = arg0.getlhs().clone();
			//find the component in arg0 that contains arg1.getLHS()
			for(IAttributeSet arg0Component: arg0.getComponents()) {
				if(arg0Component.intersects(arg1.getlhs())) {
					componentThatContainsArg1LHS.or(arg0Component);
					break;
				}
			}
			return  (componentThatContainsArg1LHS.cardinality()-componentThatContainsArg0LHS.cardinality());
			*/
			//return (arg0.getlhs().cardinality()-arg1.getlhs().cardinality());			
		}
		
	}
	
	class JTNode {
		IAttributeSet members;
		ArrayList<JTNode> children;
		JTNode parent;
		int level;
		int numChildren;

		public JTNode(IAttributeSet members) {
			this.members = members;			
			children = new ArrayList<AcyclicSchema.JTNode>();
			parent = null;
			level = 0;
			numChildren = 0;
		}		
		
		public void updateMembers(IAttributeSet members) {
			this.members=members;
		}

		public void addChild(JTNode child) {
			children.add(child);
			numChildren++;
		}
		
		public void removeChild(JTNode child) {
			children.remove(child);
			numChildren--;
		}

		public void setParent(JTNode parent) {
			this.parent = parent;
			int prevLevel = (parent == null) ? 0 : parent.level;
			this.level = prevLevel + 1;
		}

		public int getNumChildren() {
			return numChildren;
		}

		public int getLevel() {
			return level;
		}
		
		public boolean isLeaf() {
			return children.isEmpty();
		}
		
		private String SEP_STR = "separator";
		private String CLIQUE_STR = "cluster";


		//TODO: reshape this function - the ? operator is wrong
		//level even - separator, level odd - cluster
		public String toString() {
			StringBuilder sb = new StringBuilder();

			int parity = level % 2;

			String type = (parity == 1) ? CLIQUE_STR : SEP_STR;
			sb.append(type).append(", ");
			sb.append("level: ").append(level).append(": ");
			sb.append(members.toString());			
			return sb.toString();
		}
	}
	
	private JTNode getTreeClusters(Set<IAttributeSet> P){		
		ArrayList<JoinDependency> separators = new ArrayList<JoinDependency>();
		separators.addAll(JDs);
		Collections.sort(separators, new sortBySepSize()); //ensure every separator appears in exactly one component

		IAttributeSet initialComponent = new AttributeSet(numAtts);
		initialComponent.add(0, numAtts);
		JTNode root = new JTNode(initialComponent);

		Queue<JTNode> Q = new LinkedList<JTNode>();
		Q.add(root);
		IAttributeSet processedSeparators = new AttributeSet(separators.size());

		boolean treeEmpty = true;
		IAttributeSet finishedAttributes = new AttributeSet(numAtts);

		while(!Q.isEmpty()) {
			JTNode current = Q.poll();
			boolean componentSeparated = false; //default: unable to separate components

			//try to apply any one of the separators
			for(int i = 0 ; i < separators.size() && !componentSeparated; i++) {
				//already processed this separator
				if(processedSeparators.contains(i))		//already handled i-th separator
					continue;

				JoinDependency iSep = separators.get(i);
				if(current.members.contains(iSep.getlhs())) {
					//create new separator and attach it to leaf (cluster)
					IAttributeSet sepAttributes = iSep.getlhs();
					JTNode newSeparator = new JTNode(sepAttributes);
					if (treeEmpty) {
						root = newSeparator;			//by this convention, root is always a separator
						treeEmpty = false;
					} else {
						newSeparator.setParent(current);
						current.addChild(newSeparator);	//otherwise set parent-child relationship
					}
					finishedAttributes.or(sepAttributes);

					//set children for current separator node
					for(IAttributeSet JDComponent : iSep.getComponents()) {

						//current JD component is not part of cluster, but already dealt with
						if ((!current.members.contains(JDComponent)) && (finishedAttributes.contains(JDComponent)))
							continue;

						IAttributeSet newComponent = JDComponent.clone();
						newComponent.or(iSep.getlhs());
						newComponent.intersectNonConst(current.members);	//OR: not sure what this is


						if(newComponent.cardinality() > iSep.getlhs().cardinality()) {	//OR: not sure if this IF is necessary
							/*
								in order to maintain RIP property, add component to separator IF:
								1. the previous cluster contains the JD component
								2. the new separator DOESN'T contain the JD component
							 */
							if ((newSeparator.parent != null) && (newSeparator.parent.members.contains(JDComponent)) && !(newSeparator.members.contains(JDComponent))) {
								IAttributeSet JDComplement = JDComponent.complement();
								newSeparator.parent.members = newSeparator.parent.members.intersect(JDComplement); //REMOVE JDCOMPONENT
								//newComponent.or(JDComponent);
							}



							JTNode componentNode = new JTNode(newComponent);	//TODO: rename variables here
							newSeparator.addChild(componentNode);
							//OR: forgot to add parent?
							componentNode.setParent(newSeparator);
							Q.add(componentNode);

							//update finished attributes
							finishedAttributes.or(JDComponent);
						}
					}
					componentSeparated = true;
					processedSeparators.add(i);

					//remove new separator if it failed to assign children
					if(newSeparator.numChildren == 0)
						current.removeChild(newSeparator);
				}
			}
			if(!componentSeparated)
				P.add(current.members);
		}

		return root;
	}
	
	public void getSepsClusters(Set<IAttributeSet> seps, Set<IAttributeSet> clusters) {
		Queue<JTNode> Q = new LinkedList<JTNode>();
		Q.add(this.JoinTreeRepresentation);
		while(!Q.isEmpty()) {
			JTNode current = Q.poll();
			int level = current.getLevel();
			int parity = level % 2;
			if(parity == 1)
				clusters.add(current.members);
			else
				seps.add(current.members);

			for(JTNode child : current.children)
				Q.add(child);
		}
	}

	public String toString() {
	//	HashSet<IAttributeSet> components = new HashSet<IAttributeSet>();
	//	JTNode root = getTreeClusters(components);
		Queue<JTNode> Q = new LinkedList<JTNode>();
		Q.add(this.JoinTreeRepresentation);
		StringBuilder sb = new StringBuilder();
		while(!Q.isEmpty()) {
			JTNode current = Q.poll();
			sb.append(current.toString());
			sb.append(System.getProperty("line.separator"));
			for(JTNode child : current.children) {
				Q.add(child);
			}
		}
		return sb.toString();
		
	}
	
	public int getNumJDs() {
		return JDs.size();
	}

	public double getEstimatedMeasure() {
		double retVal = 0.0;
		for(JoinDependency currJD : JDs) {	
			retVal+=currJD.getMeasure();
		}
		return retVal;
	}

	/*
	OR: July 2021
	calculate J measure using formula 6:
	J = H(bags) - H(separators) - H(relation)
	 */
	public double getAccurateMeasure(double estimatedMeasure, MasterCompressedDB mcDB) {
		double Hseps  = 0.0;
		double Hbags  = 0.0;
		double Hrel   = 0.0;
		double retVal = 0.0;

		Queue<JTNode> Q = new LinkedList<JTNode>();
		Q.add(this.JoinTreeRepresentation);
		while(!Q.isEmpty()) {
			JTNode current = Q.poll();
			int currentLevel = current.getLevel();		//even = separator, odd = bag
			double entropy = mcDB.getEntropy(current.members);
			int numChildren = current.getNumChildren();
			if (current.parent == null)
				numChildren--;
			//determine if separator or bag
			if (currentLevel % 2 == 1)
				Hbags += entropy;
			else
				Hseps += entropy * numChildren;

			if(!current.children.isEmpty()) {
				for (JTNode child : current.children) {
					Q.add(child);
				}
			}
		}

		Hrel = mcDB.getTotalEntropy();

		retVal = Hbags - Hseps - Hrel;

		if (retVal < Constants.J_THRESH)
			retVal = 0;

		return retVal;
	}



	public void getJoinTreeRepresentation() {		
		JoinTreeRepresentation = getTreeClusters(JoinTreeClusters);
	}
	public int getMaxCluster() {
		int retVal=0;
		for(IAttributeSet cluster: JoinTreeClusters) {
			retVal = (cluster.cardinality() > retVal ? cluster.cardinality(): retVal);
		}
		return retVal;
	}
	
	public int getMaxSeparator() {
		int retVal=0;
		for(JoinDependency JD : JDs) {
			int keyCardinality = JD.getlhs().cardinality();
			retVal = (keyCardinality > retVal) ? keyCardinality: retVal;
		}
		return retVal;		
	}
	
	public int numClusters() {
		return JoinTreeClusters.size();
	}
	public boolean addJD(JoinDependency JD) {
		if(JDs.contains(JD))
			return false;
		for(JoinDependency currJD : JDs) {			
			if(!isCompatible(JD,currJD)) {
				System.out.println("Compatibility error: " + JD.toString() + 
						" is imcompatible with " + currJD.toString());
				return false;
			}
		}
		JDs.add(JD);
		return true;
		
	}
}