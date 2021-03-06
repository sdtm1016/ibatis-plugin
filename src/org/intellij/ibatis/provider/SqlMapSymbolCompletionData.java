package org.intellij.ibatis.provider;

import com.intellij.codeInsight.completion.CompletionVariant;
import com.intellij.codeInsight.completion.XmlCompletionData;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.Pair;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageUtil;
import com.intellij.psi.filters.TextFilter;
import com.intellij.psi.filters.TrueFilter;
import com.intellij.psi.filters.position.LeftNeighbour;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlFile;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.util.xml.DomElement;
import com.intellij.util.xml.DomFileElement;
import com.intellij.util.xml.DomManager;
import org.intellij.ibatis.model.JdbcType;
import org.intellij.ibatis.dom.sqlMap.SqlMap;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * completion data for SQL map symbol
 */
public class SqlMapSymbolCompletionData extends XmlCompletionData {
    public static String OPEN_TAG = "#";
    public static String CLOSE_TAG = "#";
    private static List<String> sentenceNames = new ArrayList<String>();
    private XmlCompletionData parentCompletionData;

    static {
        sentenceNames.add("select");
        sentenceNames.add("insert");
        sentenceNames.add("update");
        sentenceNames.add("delete");
        sentenceNames.add("procedue");
        sentenceNames.add("statement");
        sentenceNames.add("sql");
    }

    public SqlMapSymbolCompletionData(XmlCompletionData parentCompletionData) {
        this.parentCompletionData = parentCompletionData;
    }

    public String findPrefix(PsiElement psiElement, int offsetInFile) {
        if (getXmlTagForSQLCompletion(psiElement, psiElement.getContainingFile()) != null) {
            return psiElement.getText().substring(0, offsetInFile - psiElement.getTextRange().getStartOffset());
        } else
            return super.findPrefix(psiElement, offsetInFile);
    }

    @Override
    public CompletionVariant[] findVariants(PsiElement psiElement, PsiFile psiFile) {
        XmlTag tag = getXmlTagForSQLCompletion(psiElement, psiFile);
        if (tag != null) {    //
            String prefix = findPrefix(psiElement, psiElement.getTextOffset());
            String prefix2 = prefix.substring(0, prefix.indexOf("#") + 1);
            if (prefix.contains("#")) {  //# is necessary
                LeftNeighbour left = new LeftNeighbour(new TextFilter(OPEN_TAG));
                CompletionVariant variant = new CompletionVariant(left);
                variant.includeScopeClass(PsiElement.class, true);
                variant.addCompletionFilter(TrueFilter.INSTANCE);
                variant.setInsertHandler(new SqlMapSymbolnsertHandler());
                if (!prefix.contains(":")) {   //just clear in line parameter name
                    if (!prefix.contains(".")) {  //root field
                        List<String> parameterNames = getParameterNamesForXmlTag(tag, prefix2);
                        for (String parameterName : parameterNames) {
                            variant.addCompletion(parameterName);
                        }
                    } else //recursion field
                    {
                        String parameterClass = tag.getAttributeValue("parameterClass");
                        if (IbatisClassShortcutsReferenceProvider.isDomain(parameterClass))  //domain class
                        {
                            PsiClass psiClass = IbatisClassShortcutsReferenceProvider.getPsiClass(psiElement, parameterClass);
                            if (psiClass != null) { //find
                                Map<String, String> methodMap = FieldAccessMethodReferenceProvider.getAllSetterMethods(psiClass, prefix.replace("#", ""));
                                for (Map.Entry<String, String> entry : methodMap.entrySet()) {
                                    variant.addCompletion(prefix2 + entry.getKey());
                                }
                            }
                        }
                    }
                } else //jdbc type will be added
                {
                    if ((prefix + " ").split(":").length == 2) {    //only one ':' included
                        prefix = prefix.substring(0, prefix.indexOf(':'));
                        for (String typeName : JdbcType.TYPES.keySet()) {
                            variant.addCompletion(prefix + ":" + typeName);
                        }
                    } else //two ':' include
                    {

                    }
                }
                return new CompletionVariant[]{variant};
            }
        }
        if (parentCompletionData != null) return parentCompletionData.findVariants(psiElement, psiFile);
        return super.findVariants(psiElement, psiFile);
    }

    /**
     * get xml tag for code completion
     *
     * @param psiElement psiElement
     * @param psiFile    current file
     * @return xml tag
     */
    @Nullable
    public static XmlTag getXmlTagForSQLCompletion(PsiElement psiElement, PsiFile psiFile) {
        if (!psiFile.isPhysical()) {
            if (psiElement.getParent().getClass().getName().contains("com.intellij.sql.psi")) {   // text only
                  if (!psiElement.isPhysical()) {   //injected sql mode
                      //todo jacky resolve parameter code completion in sql
                      List<Pair<PsiElement,TextRange>> files = InjectedLanguageUtil.getInjectedPsiFiles(psiElement);
                      InjectedLanguageManager manager = InjectedLanguageManager.getInstance(psiElement.getProject());
                      PsiLanguageInjectionHost psiLanguageInjectionHost = manager.getInjectionHost(psiElement);
                      if (psiElement.getContainingFile() instanceof XmlFile) {
                          XmlFile xmlFile = (XmlFile) psiElement.getContainingFile();
                          final DomFileElement fileElement = DomManager.getDomManager(psiFile.getProject()).getFileElement(xmlFile, DomElement.class);
                          if (fileElement != null && fileElement.getRootElement() instanceof SqlMap) {
                              return getParentSentence(psiElement);
                          }
                      }
                  }
            }
        }
        return null;
    }

    /**
     * get parameter name list
     *
     * @param xmlTag xmlTag
     * @param prefix prefix for name
     * @return name list
     */
    public List<String> getParameterNamesForXmlTag(XmlTag xmlTag, String prefix) {
        List<String> nameList = new ArrayList<String>();
        String parameterClass = xmlTag.getAttributeValue("parameterClass");
        List<String> symbolNames = getAllSymbolsInXmlTag(xmlTag);
        if (parameterClass == null)  //if parameterClass and parameterMap absent, use #value# as default
        {
            symbolNames.add("value");
        }
        for (String symbolName : symbolNames) {
            nameList.add(prefix + symbolName + CLOSE_TAG);
        }
        return nameList;
    }

    /**
     * get all symbols for xml tag
     *
     * @param xmlTag Xml Tag
     * @return symbol name list
     */
    public static List<String> getAllSymbolsInXmlTag(XmlTag xmlTag) {
        String parameterClass = xmlTag.getAttributeValue("parameterClass");
        List<String> nameList = new ArrayList<String>();
        if (StringUtil.isNotEmpty(parameterClass)) {
            PsiClass psiClass = IbatisClassShortcutsReferenceProvider.getPsiClass(xmlTag, parameterClass);
            if (psiClass != null && !"Map".equals(psiClass.getName())) {
                if (IbatisClassShortcutsReferenceProvider.isDomain(psiClass.getName())) {   //domain class
                    Set<String> methodNames = FieldAccessMethodReferenceProvider.getAllGetterMethods(psiClass, "").keySet();
                    for (String methodName : methodNames) {
                        nameList.add(methodName);
                    }
                } else  //internal class
                {
                    nameList.add("value");
                }
            }
        }
        return nameList;
    }

    /**
     * add default symbol
     *
     * @param nameList name list
     * @param prefix   prefix
     */
    public void addDefaultSymbol(List<String> nameList, String prefix) {
        nameList.add(prefix + "value" + CLOSE_TAG);
    }

    /**
     * get SQL sentence tag for  psiElement
     *
     * @param psiElement psiElement object
     * @return XmlTag object
     */
    @Nullable
    public static XmlTag getParentSentence(PsiElement psiElement) {
        XmlTag tag = PsiTreeUtil.getParentOfType(psiElement, XmlTag.class);
        if (tag != null) {
            if (sentenceNames.contains(tag.getName())) {
                return tag;
            } else {
                return getParentSentence(tag);
            }
        }
        return null;
    }
}
